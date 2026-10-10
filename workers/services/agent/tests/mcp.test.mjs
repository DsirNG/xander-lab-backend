import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { AgentMcpService, AgentMcpError } from "../.test-build/services/agent-mcp-service.js";
import { handleAgentMcpRequest } from "../.test-build/controllers/agent-mcp-controller.js";
import { McpHttpClientMapper } from "../.test-build/mappers/mcp-http-client-mapper.js";

afterEach(() => mock.restoreAll());

function fixture() {
  const rows = [];
  const calls = [];
  let nextId = 1;
  const repository = {
    async list(ownerUserId) { return rows.filter((row) => row.ownerUserId === ownerUserId).map((row) => ({ ...row })); },
    async listEnabledVisibleTo(userId) { return rows.filter((row) => row.enabled && [0, userId].includes(row.ownerUserId)).map((row) => ({ ...row })); },
    async find(id, ownerUserId) { const row = rows.find((item) => item.id === id && item.ownerUserId === ownerUserId); return row ? { ...row } : null; },
    async findByKey(ownerUserId, key) { const row = rows.find((item) => item.ownerUserId === ownerUserId && item.serverKey === key); return row ? { ...row } : null; },
    async insert(input) {
      const row = { ...input, id: nextId++, tools: [], lastStatus: null, lastError: null, lastConnectedAt: null,
        createdAt: "now", updatedAt: "now" };
      rows.push(row);
      return { ...row };
    },
    async update(id, ownerUserId, input) {
      const row = rows.find((item) => item.id === id && item.ownerUserId === ownerUserId);
      if (!row) return false;
      Object.assign(row, input);
      return true;
    },
    async updateProbe(id, ownerUserId, values) {
      const row = rows.find((item) => item.id === id && item.ownerUserId === ownerUserId);
      if (!row) return false;
      if (values.tools) row.tools = values.tools;
      Object.assign(row, { lastStatus: values.lastStatus, lastError: values.lastError, lastConnectedAt: values.lastConnectedAt });
      return true;
    },
    async delete(id, ownerUserId) { const index = rows.findIndex((item) => item.id === id && item.ownerUserId === ownerUserId); if (index < 0) return false; rows.splice(index, 1); return true; },
  };
  const connection = {
    async listTools(server, headers) {
      calls.push(["list", server.id, headers]);
      return [{ name: "search web", description: "Search", inputSchema: { type: "object", properties: { q: { type: "string" } } } }];
    },
    async callTool(server, headers, toolName, args) { calls.push(["call", server.id, headers, toolName, args]); return { text: ["result"] }; },
  };
  const env = {
    AGENT_MCP_CLIENT_ENABLED: "true",
    AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY: Buffer.alloc(32, 7).toString("base64"),
  };
  return { service: new AgentMcpService(repository, connection, env), rows, calls };
}

test("MCP server settings are owner-scoped and never return encrypted or plain credentials", async () => {
  const { service, rows } = fixture();
  const created = await service.create(42, {
    serverKey: "search", displayName: "Search MCP", endpointUrl: "https://mcp.example/tools",
    enabled: true, headers: { Authorization: "Bearer private-token" },
  });
  assert.equal(created.ownerUserId, 42);
  assert.equal(created.headersConfigured, true);
  assert.equal(JSON.stringify(created).includes("private-token"), false);
  assert.equal(JSON.stringify(created).includes(rows[0].headersEncrypted), false);
  assert.equal((await service.list(19)).length, 0);
  assert.equal((await service.list(42)).length, 1);
  await assert.rejects(service.delete(created.id, 19), (error) => error instanceof AgentMcpError && error.status === 404);
});

test("MCP probe decrypts headers, caches bounded tool metadata, and exposes only visible tools", async () => {
  const { service, calls } = fixture();
  const created = await service.create(42, {
    serverKey: "search", displayName: "Search MCP", endpointUrl: "https://mcp.example/tools",
    enabled: true, headers: { Authorization: "Bearer private-token" },
  });
  const probed = await service.probe(created.id, 42);
  assert.equal(probed.lastStatus, "OK");
  assert.equal(calls[0][2].Authorization, "Bearer private-token");
  const tools = await service.toolsForUser(42);
  assert.equal(tools[0].fullName, "mcp__search__search_web");
  assert.equal(tools[0].serverId, created.id);
  assert.equal((await service.toolsForUser(19)).length, 0);
});

test("MCP update retains omitted credentials and an empty headers object clears them", async () => {
  const { service, rows } = fixture();
  const created = await service.create(42, {
    serverKey: "search", displayName: "Search MCP", endpointUrl: "https://mcp.example/tools",
    headers: { Authorization: "Bearer private-token" },
  });
  const original = rows[0].headersEncrypted;
  await service.update(created.id, 42, {
    serverKey: "search", displayName: "Renamed", endpointUrl: "https://mcp.example/v2", enabled: true,
  });
  assert.equal(rows[0].headersEncrypted, original);
  await service.update(created.id, 42, {
    serverKey: "search", displayName: "Renamed", endpointUrl: "https://mcp.example/v2", headers: {},
  });
  assert.equal(rows[0].headersEncrypted, null);
});

test("MCP settings reject insecure endpoints, malformed headers, and immutable-key changes", async () => {
  const { service } = fixture();
  await assert.rejects(service.create(42, { serverKey: "search", displayName: "x", endpointUrl: "http://mcp.example" }), /公网 HTTPS/);
  await assert.rejects(service.create(42, { serverKey: "search", displayName: "x", endpointUrl: "https://127.0.0.1/tools" }), /公网 DNS/);
  await assert.rejects(service.create(42, { serverKey: "search", displayName: "x", endpointUrl: "https://mcp.example", headers: { "Bad Header": "x" } }), /请求头/);
  const created = await service.create(42, { serverKey: "search", displayName: "x", endpointUrl: "https://mcp.example" });
  await assert.rejects(service.update(created.id, 42, { serverKey: "other", displayName: "x", endpointUrl: "https://mcp.example" }), /不可修改/);
});

test("Agent MCP controller enforces admin authentication and returns Java-compatible envelopes", async () => {
  const { service } = fixture();
  const auth = {
    async currentUserId() { return 42; },
    async currentAdminUserId() { return new Response(JSON.stringify({ code: 403, message: "无权访问", data: null }), { status: 403 }); },
  };
  const denied = await handleAgentMcpRequest(new Request("https://agent.example/api/admin/mcp-servers"), service, auth);
  assert.equal(denied.status, 403);
  const listed = await handleAgentMcpRequest(new Request("https://agent.example/api/agent/mcp-servers"), service, auth);
  assert.equal(listed.status, 200);
  assert.deepEqual((await listed.json()).data, []);
});

test("MCP HTTP mapper performs a Streamable HTTP handshake and carries the negotiated session", async () => {
  const requests = [];
  mock.method(globalThis, "fetch", async (input, init = {}) => {
    const url = new URL(input);
    if (url.hostname === "cloudflare-dns.com") {
      return Response.json({ Answer: url.searchParams.get("type") === "A"
        ? [{ type: 1, data: "93.184.216.34" }] : [] }, { headers: { "content-type": "application/dns-json" } });
    }
    requests.push({ url, headers: new Headers(init.headers), body: JSON.parse(init.body) });
    if (init.body.includes('"method":"initialize"')) {
      return Response.json({ jsonrpc: "2.0", id: 1, result: { protocolVersion: "2025-03-26", capabilities: {}, serverInfo: { name: "fixture" } } }, {
        headers: { "Mcp-Session-Id": "session-1", "MCP-Protocol-Version": "2025-03-26" },
      });
    }
    if (init.body.includes("notifications/initialized")) return new Response(null, { status: 202 });
    return Response.json({ jsonrpc: "2.0", id: 2, result: { tools: [
      { name: "lookup", description: "lookup text", inputSchema: { type: "object", properties: { q: { type: "string" } } } },
    ] } });
  });
  const mapper = new McpHttpClientMapper({});
  const server = { id: 4, endpointUrl: "https://mcp.example/tools" };
  const tools = await mapper.listTools(server, { Authorization: "Bearer encrypted-header-value" });
  assert.deepEqual(tools, [{ name: "lookup", description: "lookup text", inputSchema: { type: "object", properties: { q: { type: "string" } } } }]);
  assert.deepEqual(requests.map((entry) => entry.body.method), ["initialize", "notifications/initialized", "tools/list"]);
  assert.equal(requests[2].headers.get("Mcp-Session-Id"), "session-1");
  assert.equal(requests[2].headers.get("MCP-Protocol-Version"), "2025-03-26");
  assert.equal(requests[2].headers.get("Authorization"), "Bearer encrypted-header-value");
  assert.equal(requests.every((entry) => entry.url.protocol === "https:"), true);
});

test("MCP HTTP mapper refuses redirects and private literal destinations before connecting", async () => {
  let remoteCalls = 0;
  mock.method(globalThis, "fetch", async (input) => {
    if (new URL(input).hostname === "cloudflare-dns.com") {
      return Response.json({ Answer: [{ type: 1, data: "93.184.216.34" }] });
    }
    remoteCalls += 1;
    return new Response(null, { status: 302, headers: { location: "http://127.0.0.1/admin" } });
  });
  const mapper = new McpHttpClientMapper({});
  await assert.rejects(mapper.listTools({ id: 4, endpointUrl: "https://mcp.example/tools" }, {}), /重定向已拒绝/);
  assert.equal(remoteCalls, 1);
  await assert.rejects(mapper.listTools({ id: 5, endpointUrl: "https://127.0.0.1/tools" }, {}), /非公网/);
  assert.equal(remoteCalls, 1);
});
