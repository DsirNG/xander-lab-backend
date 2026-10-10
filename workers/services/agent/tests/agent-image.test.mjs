import assert from "node:assert/strict";
import test from "node:test";
import { AgentImageService } from "../.test-build/services/agent-image-service.js";
import { CoreAgentImageModelMapper } from "../.test-build/mappers/core-agent-image-model-mapper.js";
import { OpenAiAgentImageMapper } from "../.test-build/mappers/openai-agent-image-mapper.js";

const png = Uint8Array.from(Buffer.from(
  "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000b4944415408d763f8cfc000000301010018dd8db10000000049454e44ae426082",
  "hex",
));

test("Agent image model mapper uses the protected cross-account Core image-feature contract", async () => {
  let request;
  const mapper = new CoreAgentImageModelMapper({
    CORE_SERVICE_URL: "https://core.example/base",
    CORE_SERVICE_TOKEN: "service-secret",
    CORE_SERVICE: { async fetch(value) {
      request = value;
      return Response.json({ managed: true, candidates: [{
        providerId: 8, baseUrl: "https://models.example/v1", apiKey: "model-secret",
        model: "image-model", apiStyle: "IMAGES_GENERATIONS",
      }] });
    } },
  });
  const result = await mapper.candidates();
  assert.equal(new URL(request.url).pathname, "/internal/agent/image-model-config");
  assert.equal(request.headers.get("x-dinqor-service-token"), "service-secret");
  assert.deepEqual(result, { managed: true, endpoints: [{
    providerId: 8, baseUrl: "https://models.example/v1", apiKey: "model-secret",
    model: "image-model", apiStyle: "IMAGES_GENERATIONS",
  }] });
});

test("OpenAI-compatible image mapper sends bounded image requests and parses provider usage", async () => {
  let requestUrl;
  let requestBody;
  const mapper = new OpenAiAgentImageMapper(async (input, init) => {
    requestUrl = input;
    requestBody = JSON.parse(init.body);
    assert.equal(init.redirect, "manual");
    return Response.json({
      data: [{ b64_json: Buffer.from(png).toString("base64"), size: "1024x1024" }],
      usage: { input_tokens: 40, output_tokens: 160 },
    });
  });
  const result = await mapper.generate({
    providerId: 8, baseUrl: "https://models.example/v1", apiKey: "model-secret",
    model: "image-model", apiStyle: "IMAGES_GENERATIONS",
  }, "draw a small database diagram");
  assert.equal(requestUrl, "https://models.example/v1/images/generations");
  assert.equal(requestBody.model, "image-model");
  assert.equal(requestBody.prompt, "draw a small database diagram");
  assert.equal(requestBody.response_format, "b64_json");
  assert.deepEqual([...result.bytes], [...png]);
  assert.deepEqual(result.usage, { inputTokens: 40, outputTokens: 160, cachedTokens: 0 });
});

test("Agent image service records generated assets in Content and charges the Core image ledger", async () => {
  const calls = [];
  const endpoint = {
    providerId: 8, baseUrl: "https://models.example/v1", apiKey: "model-secret",
    model: "image-model", apiStyle: "IMAGES_GENERATIONS",
  };
  const service = new AgentImageService({
    async candidates() { return { managed: true, endpoints: [endpoint] }; },
  }, {
    async generate(value, prompt) {
      assert.deepEqual(value, endpoint);
      assert.match(prompt, /small database diagram/);
      return {
        bytes: png, contentType: "image/png", extension: "png", model: value.model,
        size: "1024x1024", usage: { inputTokens: 40, outputTokens: 160, cachedTokens: 0 },
      };
    },
  }, {
    async recordGenerated(...args) {
      calls.push(["media", ...args]);
      return { id: 99, url: "https://cdn.example/99.png", width: 1, height: 1, contentType: "image/png" };
    },
    async findOwned() { return null; },
  }, {
    async ensureAvailable() { return true; },
    async chargeTokens(value) { calls.push(["billing", value]); },
  });

  const result = await service.generate(42, 7, 3, {
    prompt: "small database diagram", title: "D1 illustration",
  });
  assert.equal(result.assetId, 99);
  assert.equal(result.url, "https://cdn.example/99.png");
  assert.deepEqual(JSON.parse(calls[0][5]), {
    title: "D1 illustration", prompt: "small database diagram", style: "tool",
    model: "image-model", size: "1024x1024",
  });
  assert.deepEqual(calls[1][1], {
    userId: 42, bizType: "blog-agent-illustration", bizKey: "agent-image:99", bizRef: "asset:99",
    inputTokens: 40, outputTokens: 160, cachedTokens: 0, model: "image-model", remark: "智能体生成图片",
  });
});

test("Agent image service rejects invalid prompts and refuses unmanaged missing models", async () => {
  const service = new AgentImageService(
    { async candidates() { return { managed: false, endpoints: [] }; } },
    { async generate() { throw new Error("should not be called"); } },
    { async recordGenerated() { throw new Error("should not be called"); }, async findOwned() { return null; } },
    { async ensureAvailable() { return true; }, async chargeTokens() {} },
  );
  await assert.rejects(service.generate(42, 7, 1, { prompt: "" }), /有效参数 prompt/);
  await assert.rejects(service.generate(42, 7, 1, { prompt: "valid" }), /模型尚未配置/);
});

test("Agent image resend looks up a media asset using the authenticated owner", async () => {
  let lookup;
  const service = new AgentImageService(
    { async candidates() { return { managed: false, endpoints: [] }; } },
    { async generate() { throw new Error("not used"); } },
    {
      async recordGenerated() { throw new Error("not used"); },
      async findOwned(userId, id) {
        lookup = [userId, id];
        return { id, url: "https://cdn.example/44.png", originalName: "Diagram", contentType: "image/png", width: 1, height: 1 };
      },
    },
    { async ensureAvailable() { return true; }, async chargeTokens() {} },
  );
  assert.deepEqual(await service.findOwned(42, 44), {
    assetId: 44, url: "https://cdn.example/44.png", title: "Diagram", contentType: "image/png",
  });
  assert.deepEqual(lookup, [42, 44]);
});
