import assert from "node:assert/strict";
import test from "node:test";
import { HttpModelProbeMapper } from "../.test-build/mappers/http-model-probe-mapper.js";
import { ModelProbeService } from "../.test-build/services/model-probe-service.js";
import { ModelProviderService } from "../.test-build/services/model-provider-service.js";
import { encryptSecret } from "../.test-build/services/secret-service.js";

const input = { baseUrl: "https://models.example/v1", apiKey: "test-secret", prompt: "Explain this model test." };
const json = (body, status = 200) => new Response(JSON.stringify(body), { status });

test("provider probe validates models response and does not follow redirects", async () => {
  const mapper = new HttpModelProbeMapper(async (url, init) => {
    assert.equal(url, "https://models.example/v1/models");
    assert.equal(init.method, "GET");
    assert.equal(init.redirect, "manual");
    assert.equal(init.headers.authorization, "Bearer test-secret");
    return json({ data: [] });
  });
  const result = await mapper.probe(input);
  assert.equal(result.ok, true);
  assert.equal(result.reason, "success");
  assert.equal(JSON.stringify(result).includes("test-secret"), false);
});

test("probes reject local URLs, embedded credentials and malformed addresses before fetching", async () => {
  const mapper = new HttpModelProbeMapper(() => { throw new Error("must not call upstream"); });
  for (const baseUrl of ["http://public.example", "https://127.0.0.1", "https://[::1]", "https://localhost", "https://foo.local", "https://user:pass@public.example", "https://public.example?q=secret", "invalid"]) {
    assert.equal((await mapper.probe({ ...input, baseUrl })).reason, "invalid_url");
  }
});

test("model probes use the selected protocol and require actual text output", async () => {
  for (const apiStyle of ["RESPONSES", "CHAT_COMPLETIONS"]) {
    const mapper = new HttpModelProbeMapper(async (url, init) => {
      assert.ok(url.endsWith(apiStyle === "RESPONSES" ? "/responses" : "/chat/completions"));
      const payload = JSON.parse(init.body);
      assert.equal(payload.model, "test-model");
      assert.equal(payload.stream, false);
      return json(apiStyle === "RESPONSES" ? { status: "completed", output: [{ content: [{ type: "output_text", text: "OK" }] }] } : { choices: [{ message: { content: "OK" } }] });
    });
    assert.equal((await mapper.probe({ ...input, model: "test-model", apiStyle, featureKey: "agent" })).ok, true);
  }
  for (const body of [{}, { choices: [] }, { error: { message: "test-secret" } }]) {
    const result = await new HttpModelProbeMapper(async () => json(body)).probe({ ...input, model: "m", apiStyle: "CHAT_COMPLETIONS" });
    assert.equal(result.ok, false);
    assert.equal(result.reason, body.error ? "upstream_error" : "invalid_response");
  }
});

test("model probe accepts gateway-compatible chat response text shapes", async () => {
  for (const body of [
    { choices: [{ text: "OK" }] },
    { response: "OK" },
    { data: { choices: [{ message: { content: "OK" } }] } },
  ]) {
    const result = await new HttpModelProbeMapper(async () => json(body)).probe({
      ...input, model: "m", apiStyle: "CHAT_COMPLETIONS",
    });
    assert.equal(result.ok, true, JSON.stringify(body));
    assert.equal(result.output.text, "OK");
  }
});

test("vision probe includes image input and image probes reject text-only replies", async () => {
  const vision = new HttpModelProbeMapper(async (_url, init) => {
    const payload = JSON.parse(init.body);
    assert.equal(payload.messages[0].content[1].type, "image_url");
    return json({ choices: [{ message: { content: "A white image" } }] });
  });
  assert.equal((await vision.probe({ ...input, model: "vision", apiStyle: "CHAT_COMPLETIONS", featureKey: "img2three_vision", imageDataUrl: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAFElEQVR4nGP4TyJgGNUwqmH4agAAr639H708R/EAAAAASUVORK5CYII=" })).ok, true);
  for (const apiStyle of ["IMAGES_GENERATIONS", "CHAT_COMPLETIONS", "CHAT_PROMPT"]) {
    const textOnly = new HttpModelProbeMapper(async () => json({ choices: [{ message: { content: "OK" } }] }));
    assert.equal((await textOnly.probe({ ...input, model: "image", apiStyle, featureKey: "blog_agent_image" })).ok, false);
    const images = new HttpModelProbeMapper(async () => json({ data: [{ b64_json: "iVBOR" }] }));
    assert.equal((await images.probe({ ...input, model: "image", apiStyle, featureKey: "blog_agent_image" })).ok, true);
  }
});

test("probe normalizes upstream failures without disclosing response bodies", async () => {
  for (const [status, reason] of [[401, "authentication"], [403, "authentication"], [404, "not_found"], [429, "rate_limited"], [500, "upstream_error"], [302, "upstream_error"]]) {
    const result = await new HttpModelProbeMapper(async () => json({ message: "test-secret" }, status)).probe(input);
    assert.equal(result.reason, reason);
    assert.equal(result.upstreamStatus, status);
    assert.equal(JSON.stringify(result).includes("test-secret"), false);
  }
  assert.equal((await new HttpModelProbeMapper(async () => new Response("<html>OK</html>")).probe(input)).reason, "invalid_response");
  assert.equal((await new HttpModelProbeMapper(async () => { throw new Error("secret-host"); }).probe(input)).reason, "connection");
});

test("saved provider key is reused while draft URL and key overrides are never persisted", async () => {
  const encryptionKey = Buffer.alloc(32, 9).toString("base64");
  const encryptedApiKey = await encryptSecret("saved-key", encryptionKey);
  const provider = { id: 1, enabled: 1, baseUrl: input.baseUrl, encryptedApiKey };
  const providers = new ModelProviderService({ findProvider: async () => provider });
  const calls = [];
  const service = new ModelProbeService(providers, { probe: async (value) => { calls.push(value); return { ok: true, reason: "success", latencyMs: 1 }; } });
  await service.test({ providerId: 1 }, encryptionKey);
  await service.test({ providerId: 1, apiKey: "draft-key", baseUrl: "https://draft.example/v1" }, encryptionKey);
  assert.equal(calls[0].apiKey, "saved-key");
  assert.equal(calls[1].apiKey, "draft-key");
  assert.equal(calls[1].baseUrl, "https://draft.example/v1");
  assert.equal(provider.baseUrl, input.baseUrl);
  assert.equal(provider.encryptedApiKey, encryptedApiKey);
});

test("ASR probe explicitly requires real audio instead of reporting a false success", async () => {
  const mapper = new HttpModelProbeMapper(() => { throw new Error("must not call upstream"); });
  const result = await mapper.probe({ ...input, featureKey: "recitation_asr", apiStyle: "DASHSCOPE_ASR", model: "paraformer-v2" });
  assert.equal(result.ok, false);
  assert.equal(result.reason, "audio_required");
});


test("administrator prompts and successful conversation history reach the selected protocol unchanged", async () => {
  const messages = [{ role: "user", content: "First question" }, { role: "assistant", content: "First answer" }];
  for (const apiStyle of ["RESPONSES", "CHAT_COMPLETIONS"]) {
    const mapper = new HttpModelProbeMapper(async (_url, init) => {
      const payload = JSON.parse(init.body);
      assert.deepEqual(apiStyle === "RESPONSES" ? payload.input : payload.messages, [...messages, { role: "user", content: "My own follow-up" }]);
      assert.equal(payload.max_tokens, undefined);
      assert.equal(payload.max_completion_tokens, undefined);
      assert.equal(payload.max_output_tokens, undefined);
      return json(apiStyle === "RESPONSES" ? { output_text: "The actual answer" } : { choices: [{ message: { content: "The actual answer" } }] });
    });
    const result = await mapper.probe({ ...input, model: "chosen-model", featureKey: "agent", apiStyle, prompt: "My own follow-up", messages });
    assert.equal(result.output.text, "The actual answer");
  }
});

test("model requests require an explicit prompt and never invent a test generation", async () => {
  let calls = 0;
  const mapper = new HttpModelProbeMapper(async () => { calls++; return json({}); });
  const result = await mapper.probe({ ...input, prompt: "", model: "image", featureKey: "blog_agent_image", apiStyle: "IMAGES_GENERATIONS" });
  assert.equal(result.reason, "prompt_required");
  assert.equal(calls, 0);
});

test("image prompt is provided by the administrator and optional parameters are omitted by default", async () => {
  const mapper = new HttpModelProbeMapper(async (_url, init) => {
    const payload = JSON.parse(init.body);
    assert.equal(payload.prompt, "A red train in the snow");
    assert.equal(payload.size, undefined);
    assert.equal(payload.response_format, undefined);
    return json({ data: [{ url: "https://images.example/result.png" }] });
  });
  const result = await mapper.probe({ ...input, model: "image-model", featureKey: "blog_agent_image", apiStyle: "IMAGES_GENERATIONS", prompt: "A red train in the snow" });
  assert.deepEqual(result.output.images, ["https://images.example/result.png"]);
});

test("HTTP 400 retains useful provider diagnostics but redacts keys and excludes raw payloads and stacks", async () => {
  const mapper = new HttpModelProbeMapper(async () => new Response(JSON.stringify({
    error: { message: "response_format is unsupported; key=test-secret; Authorization: Bearer another-private-token", code: "unsupported_parameter", type: "invalid_request_error", param: "response_format", stack: "private stack" },
    private: "must never be returned",
  }), { status: 400, headers: { "content-type": "application/json", "x-request-id": "req_400_example" } }));
  const result = await mapper.probe({ ...input, model: "image-model", featureKey: "blog_agent_image", apiStyle: "IMAGES_GENERATIONS" });
  assert.equal(result.ok, false);
  assert.equal(result.upstreamStatus, 400);
  assert.equal(result.error.code, "unsupported_parameter");
  assert.equal(result.error.param, "response_format");
  assert.equal(result.error.type, "invalid_request_error");
  assert.equal(result.error.requestId, "req_400_example");
  assert.match(result.error.message, /response_format is unsupported/);
  assert.equal(JSON.stringify(result).includes("test-secret"), false);
  assert.equal(JSON.stringify(result).includes("another-private-token"), false);
  assert.equal(JSON.stringify(result).includes("private stack"), false);
  assert.equal(JSON.stringify(result).includes("must never be returned"), false);
});

test("oversized provider responses are cancelled rather than buffered indefinitely", async () => {
  let cancelled = false;
  const mapper = new HttpModelProbeMapper(async () => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(20 * 1024 * 1024 + 1)); },
    cancel() { cancelled = true; },
  })));
  const result = await mapper.probe(input);
  assert.equal(result.reason, "response_too_large");
  assert.equal(cancelled, true);
});

test("provider timeouts are reported and release their timer", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const mapper = new HttpModelProbeMapper((_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
  }));
  const pending = mapper.probe(input);
  t.mock.timers.tick(60_000);
  assert.equal((await pending).reason, "timeout");
});
