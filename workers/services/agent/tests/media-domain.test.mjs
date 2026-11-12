import test from "node:test";
import assert from "node:assert/strict";
import { AgentConversationService } from "../.test-build/services/agent-conversation-service.js";
const old = "https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media/3/2026/11/f12134ac-8e69-4b08-ae6a-20694f896466.png";
const base = "https://api.example/api/public/blog/media";
test("history and workspace image URLs use custom domain without modifying persisted content", async () => {
  const row = { content: `![dog](${old})`, attachmentsJson: null };
  const repository = {
    async findOwnedConversation(user, id) { return user === 3 && id === 230 ? { id, userId: user } : null; },
    async listMessages() { return [row, { content: "https://other.example/api/public/blog/media/a.png", attachmentsJson: null }]; },
    async listWorkspaceResults() { return [{ dataContent: JSON.stringify({ url: old }) }]; },
  };
  const service = new AgentConversationService(repository, undefined, base);
  const result = await service.get(3, 230);
  assert.equal(result.messages[0].content, `![dog](${old.replace(old.split("/api/")[0]+"/api/public/blog/media", base)})`);
  assert.equal(JSON.parse(result.workspaceResults[0].dataContent).url.startsWith(base), true);
  assert.equal(result.messages[1].content, "https://other.example/api/public/blog/media/a.png");
  assert.equal(row.content, `![dog](${old})`);
  assert.equal(await service.get(4, 230), null);
});


import { OssAgentImageUrlMapper } from "../.test-build/mappers/oss-agent-image-url-mapper.js";
test("image history signs owner keys, refreshes expired OSS queries and ignores foreign hosts and owners", async () => {
  const mapper = new OssAgentImageUrlMapper({
    ALIYUN_OSS_ENDPOINT: "oss-cn-beijing.aliyuncs.com", ALIYUN_OSS_BUCKET: "test-images",
    ALIYUN_OSS_ACCESS_KEY_ID: "test-id", ALIYUN_OSS_ACCESS_KEY_SECRET: "test-secret",
    AGENT_MEDIA_PUBLIC_BASE_URL: base,
  });
  const direct = old.replace("https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media", "https://test-images.oss-cn-beijing.aliyuncs.com/photos/blog");
  const foreign = old.replace("/media/3/", "/media/4/");
  const other = old.replace("dinqor-content-automation.dinqor.workers.dev", "untrusted.example");
  const result = await mapper.refresh(3, [`![dog](${old})`, JSON.stringify({ url: direct + "?expired=1" }), foreign, other, "https://%"]);
  assert.match(result[0], /https:\/\/test-images\.oss-cn-beijing\.aliyuncs\.com\/photos\/blog\/3\//);
  const url = new URL(JSON.parse(result[1]).url);
  assert.equal(url.searchParams.get("x-oss-expires"), "86400");
  assert.equal(url.searchParams.has("expired"), false);
  assert.match(url.searchParams.get("x-oss-signature"), /^[0-9a-f]{64}$/);
  assert.equal(result[2], foreign);
  assert.equal(result[3], other);
  assert.equal(result[4], "https://%");
});
