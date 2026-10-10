import assert from "node:assert/strict";
import test from "node:test";
import { handleAgentWebSocketRequest } from "../.test-build/controllers/agent-websocket-controller.js";

class FakeSocket {
  listeners = new Map();
  sent = [];
  accepted = false;
  closed = null;
  accept() { this.accepted = true; }
  send(value) { this.sent.push(value); }
  close(code = 1000, reason = "") { this.closed = { code, reason }; }
  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) ?? [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }
  receive(value) {
    for (const listener of this.listeners.get("message") ?? []) listener({ data: value });
  }
}

function wsRequest(path = "/ws/agent?conversationId=7&runVersion=4") {
  return new Request(`https://agent.example.test${path}`, { headers: { upgrade: "websocket" } });
}

test("Agent WebSocket handshake checks the projected owner and required run identity", async () => {
  let pairs = 0;
  const service = { async getConversation() { return { id: 7, runVersion: 4, status: "running" }; } };
  const denied = await handleAgentWebSocketRequest(
    wsRequest(), service, async () => Response.json({ code: 401 }, { status: 401 }), undefined,
    () => { pairs++; return null; },
  );
  assert.equal(denied.status, 401);
  assert.equal(pairs, 0);

  const invalid = await handleAgentWebSocketRequest(
    wsRequest("/ws/agent?conversationId=7"), service, async () => 42, undefined,
    () => { pairs++; return null; },
  );
  assert.equal(invalid.status, 400);
  assert.equal(pairs, 0);
});

test("Agent WebSocket replays durable events and closes after the terminal event", async () => {
  const client = new FakeSocket();
  const server = new FakeSocket();
  let eventPage = 0;
  const service = {
    async getConversation(userId, conversationId) {
      assert.equal(userId, 42);
      assert.equal(conversationId, 7);
      return { id: 7, runVersion: 4, status: "running" };
    },
    async listEventsAfter(conversationId, runVersion, afterEventId) {
      assert.equal(conversationId, 7);
      assert.equal(runVersion, 4);
      if (eventPage++ === 0) {
        assert.equal(afterEventId, 10);
        return [{ id: 11, runVersion: 4, eventType: "answer_delta", dataContent: "hello" }];
      }
      return [{ id: 12, runVersion: 4, eventType: "complete", dataContent: '{"status":"ready"}' }];
    },
  };
  let background;
  const response = await handleAgentWebSocketRequest(
    wsRequest("/ws/agent?conversationId=7&runVersion=4&afterEventId=10"),
    service,
    async () => 42,
    { waitUntil(promise) { background = promise; } },
    () => ({ client, server }),
    () => new Response(null, { status: 200, headers: { "x-test-upgrade": "mock" } }),
  );
  assert.equal(response.status, 200);
  assert.equal(server.accepted, true);
  await background;
  assert.deepEqual(server.sent.map((value) => JSON.parse(value)), [
    { id: 11, event: "answer_delta", data: "hello" },
    { id: 12, event: "complete", data: { status: "ready" } },
  ]);
  assert.deepEqual(server.closed, { code: 1000, reason: "turn complete" });
});

test("Agent WebSocket responds to ping with pong", async () => {
  const client = new FakeSocket();
  const server = new FakeSocket();
  const service = {
    async getConversation() { return { id: 7, runVersion: 4, status: "running" }; },
    async listEventsAfter() { return []; },
  };
  let background;
  const response = await handleAgentWebSocketRequest(
    wsRequest(), service, async () => 42, { waitUntil(promise) { background = promise; } },
    () => ({ client, server }),
    () => new Response(null, { status: 200 }),
  );
  server.receive('{"event":"ping"}');
  assert.deepEqual(server.sent.map((value) => JSON.parse(value)), [{ event: "pong" }]);
  server.close();
  for (const listener of server.listeners.get("close") ?? []) listener();
  await background;
  assert.equal(response.status, 200);
});
