import assert from "node:assert/strict";
import test from "node:test";
import { handleBlogMcpRequest } from "../.test-build/controllers/blog-mcp-controller.js";
import { BlogMcpService } from "../.test-build/services/blog-mcp-service.js";

test("protected MCP article contract delegates owner-scoped create and retry identity to Content", async () => {
  const creations = [];
  const creation = {
    async create(input) { creations.push(input); return { id: 501, userId: input.userId, title: input.title, content: input.content }; },
  };
  const management = {
    async listMine(...args) { return { records: [], total: 0, ownerArgs: args }; },
    async findMine(userId, postId) { return { id: postId, userId, title: "owned" }; },
    async update(userId, postId, input) { return { id: postId, userId, input }; },
    async moveToTrash() {},
  };
  const service = new BlogMcpService(creation, management);
  const request = new Request("https://content.test/internal/platform-integration/mcp/blog", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ operation: "create", userId: 44, idempotencyKey: "mcp:call-1", title: "Draft", content: "Body", publish: false }),
  });
  const response = await handleBlogMcpRequest(request, service);
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.userId, 44);
  assert.equal(creations[0].userId, 44);
  assert.equal(creations[0].idempotencyKey, "mcp:call-1");
  assert.equal(creations[0].publish, false);
  assert.equal(creations[0].categoryId, "backend");
});

test("Content MCP article contract enforces owner and bounds list payloads", async () => {
  const calls = [];
  const service = new BlogMcpService({}, {
    async listMine(...args) {
      calls.push(args);
      return { records: [{ id: 91, title: "A", content: "large OSS body" }], total: 1 };
    },
  });
  const response = await handleBlogMcpRequest(new Request("https://content.test/internal/platform-integration/mcp/blog", {
    method: "POST", body: JSON.stringify({ operation: "list", userId: 17, page: 500, size: 50, search: "draft" }),
  }), service);
  assert.equal(response.status, 200);
  assert.deepEqual(calls[0], [17, null, "draft", 10, 10]);
  assert.deepEqual(await response.json(), { records: [{ id: 91, title: "A" }], total: 1 });

  const badOwner = await handleBlogMcpRequest(new Request("https://content.test/internal/platform-integration/mcp/blog", {
    method: "POST", body: JSON.stringify({ operation: "list", userId: "17" }),
  }), service);
  assert.equal(badOwner.status, 400);
});
