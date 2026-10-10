import assert from "node:assert/strict";
import test from "node:test";
import { handleMcpTransportRequest } from "../.test-build/controllers/mcp-transport-controller.js";
import { PlatformMcpService } from "../.test-build/services/platform-mcp-service.js";

function fixture() {
  const calls = [];
  const principalByToken = new Map([
    ["full", { userId: 22, clientId: "client-a", scopes: ["blog:write", "blog:read", "csdn:publish", "csdn:status", "csdn:authorize"] }],
    ["read", { userId: 23, clientId: "client-b", scopes: ["blog:read"] }],
  ]);
  const oauth = {
    async authenticateBearer(value) {
      return value?.startsWith("Bearer ") ? principalByToken.get(value.slice(7)) ?? null : null;
    },
    publicBaseUrl() { return "https://dinqor.cn"; },
  };
  const blog = {
    async list(...args) { calls.push(["list", ...args]); return { records: [] }; },
    async get(...args) { calls.push(["get", ...args]); return { id: args[1], userId: args[0] }; },
    async create(input) { calls.push(["create", input]); return { id: 601 }; },
    async update(...args) { calls.push(["update", ...args]); return { id: args[1] }; },
    async delete(...args) { calls.push(["delete", ...args]); },
  };
  const publication = { async sync(input) { calls.push(["sync", input]); return { ...input, externalId: "provider-7", url: "https://provider.test/7" }; } };
  const billing = {
    async flatPoints(type) { calls.push(["price", type]); return 10; },
    async charge(input) { calls.push(["charge", input]); },
  };
  const authorization = { async status(...args) { calls.push(["status", ...args]); return { status: "AUTHORIZED" }; }, async start(...args) { calls.push(["start", ...args]); return { status: "PENDING", qrCodeDataUrl: "data:image/png;base64,qr" }; } };
  const credentials = { async status(...args) { calls.push(["credential-status", ...args]); return { status: "NOT_AUTHORIZED" }; } };
  return {
    calls, oauth,
    service: new PlatformMcpService(blog, publication, billing, authorization, credentials,
      { blog: true, csdn: true, dual: true, juejin: true }),
  };
}

function request(path, method, id, params, token) {
  return new Request(`https://dinqor.cn${path}`, {
    method: "POST",
    headers: { accept: "application/json, text/event-stream", "content-type": "application/json", authorization: `Bearer ${token}` },
    body: JSON.stringify({ jsonrpc: "2.0", id, method, ...(params ? { params } : {}) }),
  });
}

test("MCP Streamable HTTP challenge, initialize, discovery and scope checks follow OAuth identity", async () => {
  const value = fixture();
  const challenged = await handleMcpTransportRequest(new Request("https://dinqor.cn/api/mcp/csdn", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize" }),
  }), value.oauth, value.service);
  assert.equal(challenged.status, 401);
  assert.match(challenged.headers.get("www-authenticate"), /oauth-protected-resource/);

  const initialized = await handleMcpTransportRequest(request("/api/mcp", "initialize", 1, {
    protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "test", version: "1" },
  }, "full"), value.oauth, value.service);
  const initializedBody = await initialized.json();
  assert.equal(initializedBody.result.serverInfo.name, "xander-blog");

  const tools = await handleMcpTransportRequest(request("/api/mcp/csdn", "tools/list", 2, {}, "full"), value.oauth, value.service);
  assert.equal((await tools.json()).result.tools.some((tool) => tool.name === "sync_post_to_csdn"), true);
  const refused = await handleMcpTransportRequest(request("/api/mcp/csdn", "tools/call", 3, {
    name: "sync_post_to_csdn", arguments: { postId: 88 },
  }, "read"), value.oauth, value.service);
  const refusedBody = await refused.json();
  assert.equal(refusedBody.result.isError, true);
  assert.equal(value.calls.some((call) => call[0] === "sync"), false);

  const published = await handleMcpTransportRequest(request("/api/mcp/csdn", "tools/call", 4, {
    name: "sync_post_to_csdn", arguments: { postId: 88 },
  }, "full"), value.oauth, value.service);
  assert.equal((await published.json()).result.structuredContent.externalId, "provider-7");
  const sync = value.calls.find((call) => call[0] === "sync")[1];
  assert.deepEqual(sync, { userId: 22, postId: 88, platform: "csdn" });
  assert.equal(value.calls.find((call) => call[0] === "charge")[1].bizKey, "csdn:88");
});

test("MCP blog tools use the OAuth owner and idempotent content request, never caller identity headers", async () => {
  const value = fixture();
  const response = await handleMcpTransportRequest(request("/mcp", "tools/call", "turn-1", {
    name: "publish_post", arguments: { title: "Hello", contentBase64: Buffer.from("large enough article body").toString("base64") },
  }, "full"), value.oauth, value.service);
  const body = await response.json();
  assert.equal(body.result.structuredContent.postId, 601);
  const create = value.calls.find((call) => call[0] === "create")[1];
  assert.equal(create.userId, 22);
  assert.equal(create.publish, true);
  assert.match(create.idempotencyKey, /^mcp:/);
});

test("MCP rejects invalid JSON-RPC ids before executing a tool", async () => {
  const value = fixture();
  const response = await handleMcpTransportRequest(request("/api/mcp/csdn", "tools/call", true, {
    name: "sync_post_to_csdn", arguments: { postId: 88 },
  }, "full"), value.oauth, value.service);
  const body = await response.json();
  assert.equal(response.status, 400);
  assert.equal(body.error.code, -32600);
  assert.equal(value.calls.some((call) => call[0] === "sync" || call[0] === "charge"), false);
});
