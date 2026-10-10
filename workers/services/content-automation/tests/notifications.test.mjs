import assert from "node:assert/strict";
import test from "node:test";
import { handleContentNotificationRequest } from "../.test-build/controllers/content-notification-controller.js";
import { ContentNotificationService } from "../.test-build/services/content-notification-service.js";

function setup() {
  const rows = [];
  const streams = new Map();
  const broadcasts = [];
  let nextId = 1;
  let nextBroadcastId = 1;
  const repository = {
    async list(userId, limit, offset) {
      return rows.filter((row) => row.userId === userId).slice().reverse().slice(offset, offset + limit).map(toView);
    },
    async listAfter(userId, afterId, limit) {
      return rows.filter((row) => row.userId === userId && row.id > afterId).slice(0, limit).map(toView);
    },
    async count(userId) { return rows.filter((row) => row.userId === userId).length; },
    async unreadCount(userId) { return rows.filter((row) => row.userId === userId && !row.isRead).length; },
    async markRead(userId, id) {
      const row = rows.find((item) => item.userId === userId && item.id === id);
      if (!row) return false;
      row.isRead = true;
      return true;
    },
    async markAllRead(userId) { for (const row of rows) if (row.userId === userId) row.isRead = true; },
    async create(input) {
      const row = { ...input, id: nextId++, isRead: false, createdAt: "2026-10-10T00:00:00.000Z" };
      rows.push(row);
      return row.id;
    },
    async openStream(connectionId, ownerId, nowMs) {
      streams.set(connectionId, { ownerId, leaseUntilMs: nowMs + 45_000 });
      return broadcasts.at(-1)?.id ?? 0;
    },
    async heartbeatStream(connectionId, nowMs) {
      const stream = streams.get(connectionId);
      if (stream) stream.leaseUntilMs = nowMs + 45_000;
    },
    async closeStream(connectionId) { streams.delete(connectionId); },
    async listBroadcastsAfter(afterId, nowMs, limit) {
      return broadcasts.filter((row) => row.id > afterId && row.expiresAtMs > nowMs).slice(0, limit);
    },
    async broadcastTest(nowMs, expiresAtMs) {
      const recipients = [...streams.values()].filter((stream) => stream.leaseUntilMs > nowMs).length;
      broadcasts.push({ id: nextBroadcastId++, type: "SSE_TEST_BROADCAST", title: "SSE 广播测试",
        message: "管理员发起的实时通知测试", expiresAtMs });
      return recipients;
    },
    async cleanupExpiredStreams(nowMs) {
      for (const [id, stream] of streams) if (stream.leaseUntilMs <= nowMs) streams.delete(id);
      for (let index = broadcasts.length - 1; index >= 0; index--) {
        if (broadcasts[index].expiresAtMs <= nowMs) broadcasts.splice(index, 1);
      }
    },
  };
  const service = new ContentNotificationService(repository);
  return { rows, streams, broadcasts, repository, service };
}

function toView(row) {
  const { userId, ...view } = row;
  return view;
}

test("notification APIs create internal records, page owner-scoped rows, and mark read idempotently", async () => {
  const { service } = setup();
  const internal = await handleContentNotificationRequest(new Request("https://content/internal/content/notifications", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ userId: 42, type: "RUN_FAILED", title: "Run failed", message: "Safe error", planId: 3 }),
  }), service);
  assert.deepEqual(await internal.json(), { id: 1 });

  const list = await handleContentNotificationRequest(new Request("https://content/api/notifications?page=1&size=10", {
    headers: { "x-user-id": "42" },
  }), service);
  assert.equal(list.status, 200);
  assert.deepEqual((await list.json()).data, {
    records: [{ id: 1, type: "RUN_FAILED", title: "Run failed", message: "Safe error", planId: 3,
      runId: null, resourceType: null, resourceId: null, isRead: false, createdAt: "2026-10-10T00:00:00.000Z" }],
    total: 1,
    unreadCount: 1,
  });

  const crossOwner = await handleContentNotificationRequest(new Request("https://content/api/notifications/1/read", {
    method: "PATCH", headers: { "x-user-id": "7" },
  }), service);
  assert.equal(crossOwner.status, 404);
  const marked = await handleContentNotificationRequest(new Request("https://content/api/notifications/1/read", {
    method: "PATCH", headers: { "x-user-id": "42" },
  }), service);
  assert.equal(marked.status, 200);
  const count = await handleContentNotificationRequest(new Request("https://content/api/notifications/unread-count", {
    headers: { "x-user-id": "42" },
  }), service);
  assert.equal((await count.json()).data, 0);
});

test("notification event stream emits owner notifications after its cursor", async () => {
  const { service } = setup();
  await service.create({ userId: 42, type: "RUN_FAILED", title: "Run failed", message: "Safe error" });
  const response = await handleContentNotificationRequest(new Request("https://content/api/notifications/events?afterId=0", {
    headers: { "x-user-id": "42" },
  }), service);
  assert.equal(response.headers.get("content-type"), "text/event-stream; charset=utf-8");
  const reader = response.body.getReader();
  const first = await reader.read();
  assert.match(new TextDecoder().decode(first.value), /: connected/);
  const notification = await reader.read();
  const event = new TextDecoder().decode(notification.value);
  assert.match(event, /id: 1/);
  assert.match(event, /event: notification/);
  await reader.cancel();
});

test("notification SSE registers active connections and streams a live-only admin broadcast", async () => {
  const { streams, repository, service } = setup();
  let releaseList;
  const waitingForBroadcast = new Promise((resolve) => { releaseList = resolve; });
  repository.listAfter = async () => {
    await waitingForBroadcast;
    return [];
  };
  const response = await handleContentNotificationRequest(new Request("https://content/api/notifications/events?afterId=0", {
    headers: { "x-user-id": "42" },
  }), service);
  const reader = response.body.getReader();
  assert.match(new TextDecoder().decode((await reader.read()).value), /: connected/);
  assert.equal(streams.size, 1);

  const broadcastResponse = await handleContentNotificationRequest(new Request(
    "https://content/internal/content/notifications/test-broadcast", { method: "POST" },
  ), service);
  assert.deepEqual(await broadcastResponse.json(), { recipients: 1 });
  releaseList();
  const event = new TextDecoder().decode((await reader.read()).value);
  assert.match(event, /event: notification/);
  assert.match(event, /"type":"SSE_TEST_BROADCAST"/);
  assert.match(event, /"planId":"","runId":""/);
  assert.equal((await service.list(42, 1, 10)).total, 0, "diagnostic broadcasts must not be persisted as owner notifications");
  await reader.cancel();
  await Promise.resolve();
  assert.equal(streams.size, 0);
});
