import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { handleBlogAgentModelRequest } from "../.test-build/controllers/blog-agent-model-controller.js";
import { CoreBlogAgentModelMapper } from "../.test-build/mappers/core-blog-agent-model-mapper.js";
import { BlogAgentModelService } from "../.test-build/services/blog-agent-model-service.js";

afterEach(() => mock.restoreAll());

test("Core blog model service maps Java stages to scoped prompts and validates inputs", async () => {
  const calls = [];
  const service = new BlogAgentModelService({
    async complete(input) {
      calls.push(input);
      return { payloadJson: JSON.stringify({ pass: true, reasons: [] }), inputTokens: 4, cachedTokens: 0,
        outputTokens: 2, model: "blog-model" };
    },
  }, true);

  const result = await service.execute("compliance", { title: "A title", content: "Safe article" });
  assert.equal(JSON.parse(result.payloadJson).pass, true);
  assert.match(calls[0].instruction, /内容安全审核员/);
  assert.match(calls[0].input, /Safe article/);
  assert.equal(calls[0].webSearch, false);

  await service.execute("research", { input: "JWT", analysisJson: "{\"angle\":\"claims\"}" });
  assert.equal(calls[1].webSearch, true);
  assert.match(calls[1].input, /\"angle\":\"claims\"/);
  await assert.rejects(service.execute("research", { input: "JWT", analysisJson: "not-json" }),
    (error) => error.status === 400);
  await assert.rejects(service.execute("unknown", {}), (error) => error.status === 404);
});

test("Core blog topic-series service removes excluded and duplicate titles", async () => {
  const calls = [];
  const service = new BlogAgentModelService({
    async complete(input) {
      calls.push(input);
      return { payloadJson: JSON.stringify({ topics: ["A deeper D1 migration", "Used title", "a deeper d1 migration", "Gateway routing"] }),
        inputTokens: 10, cachedTokens: 0, outputTokens: 8, model: "blog-model" };
    },
  });
  const result = await service.generateTopics({
    seed: "Cloudflare D1 migrations", count: 5, audience: "backend developers",
    excludeTitles: ["Used title"],
  });
  assert.deepEqual(result.topics, ["A deeper D1 migration", "Gateway routing"]);
  assert.match(calls[0].instruction, /5 个彼此独立/);
  assert.match(calls[0].instruction, /Used title/);
  assert.match(calls[0].input, /backend developers/);
  assert.equal(calls[0].webSearch, false);
  await assert.rejects(service.generateTopics({ seed: "D1", count: 31 }), (error) => error.status === 400);
});

test("Core blog model mapper calls Responses and Chat Completions and preserves usage", async () => {
  const endpoints = [
    { providerId: 1, baseUrl: "https://models.example/v1", apiKey: "responses-key", model: "blog-r", apiStyle: "RESPONSES" },
    { providerId: 2, baseUrl: "https://models.example/v1", apiKey: "chat-key", model: "blog-c", apiStyle: "CHAT_COMPLETIONS" },
  ];
  const mapper = new CoreBlogAgentModelMapper({ async candidates() { return endpoints; } });
  const requests = [];
  mock.method(globalThis, "fetch", async (input, init) => {
    requests.push({ input, headers: new Headers(init.headers), body: JSON.parse(init.body) });
    if (requests.length === 1) {
      return Response.json({ output_text: "```json\n{\"title\":\"Blog\"}\n```", usage: {
        input_tokens: 15, output_tokens: 7, input_tokens_details: { cached_tokens: 2 },
      } });
    }
    return Response.json({ choices: [{ message: { content: "{\"pass\":true}" } }], usage: {
      prompt_tokens: 10, completion_tokens: 3, prompt_tokens_details: { cached_tokens: 1 },
    } });
  });

  const response = await mapper.complete({ instruction: "Return JSON", input: "A topic", webSearch: true });
  assert.deepEqual(JSON.parse(response.payloadJson), { title: "Blog" });
  assert.deepEqual([response.inputTokens, response.cachedTokens, response.outputTokens, response.model], [15, 2, 7, "blog-r"]);
  assert.equal(requests[0].input, "https://models.example/v1/responses");
  assert.equal(requests[0].headers.get("authorization"), "Bearer responses-key");
  assert.deepEqual(requests[0].body.tools, [{ type: "web_search" }]);

  endpoints.splice(0, 1);
  const chat = await mapper.complete({ instruction: "Review", input: "Article" });
  assert.deepEqual(JSON.parse(chat.payloadJson), { pass: true });
  assert.deepEqual([chat.inputTokens, chat.cachedTokens, chat.outputTokens, chat.model], [10, 1, 3, "blog-c"]);
  assert.equal(requests[1].input, "https://models.example/v1/chat/completions");
  assert.equal(requests[1].body.response_format.type, "json_object");
});

test("Core blog model internal controller keeps the existing service-token contract response shape", async () => {
  const service = new BlogAgentModelService({
    async complete() {
      return { payloadJson: "{\"angle\":\"mechanism\"}", inputTokens: 12, cachedTokens: 3,
        outputTokens: 5, model: "blog-model" };
    },
  });
  const response = await handleBlogAgentModelRequest(new Request("https://core.example/internal/agent/blog/model/analyze", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ input: "JWT" }),
  }), service);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    payloadJson: "{\"angle\":\"mechanism\"}", inputTokens: 12, cachedTokens: 3,
    outputTokens: 5, model: "blog-model",
  });
});

test("Core internal topic-series route preserves the Content contract", async () => {
  const service = new BlogAgentModelService({
    async complete() {
      return { payloadJson: "{\"topics\":[\"One\",\"Two\"]}", inputTokens: 2, cachedTokens: 0,
        outputTokens: 3, model: "blog-model" };
    },
  });
  const response = await handleBlogAgentModelRequest(new Request("https://core.example/internal/agent/blog/topic-series", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ userId: 42, seed: "Seed", count: 2 }),
  }), service);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { topics: ["One", "Two"] });
});

test("Core model mapper does not retry a non-transient provider response", async () => {
  const mapper = new CoreBlogAgentModelMapper({
    async candidates() {
      return [{ providerId: 1, baseUrl: "https://models.example/v1", apiKey: "key", model: "blog", apiStyle: "RESPONSES" }];
    },
  });
  let calls = 0;
  mock.method(globalThis, "fetch", async () => {
    calls += 1;
    return new Response("{}", { status: 400 });
  });
  await assert.rejects(mapper.complete({ instruction: "Return JSON", input: "A topic" }), /HTTP 400/);
  assert.equal(calls, 1);
});
