import assert from "node:assert/strict";
import test from "node:test";
import { AgentWebResearchService, publicWebUrl } from "../.test-build/services/agent-web-research-service.js";
import { TavilyWebResearchMapper } from "../.test-build/mappers/tavily-web-research-mapper.js";
import { createWebResearchTools } from "../.test-build/services/agent-web-tool-adapters.js";
import { AgentToolExecutionPipeline } from "../.test-build/services/agent-tool-runtime.js";
import { SUBTASK_READ_ONLY_TOOLS } from "../.test-build/services/agent-tool-definitions.js";

const signal = () => new AbortController().signal;
const source = (url = "https://example.com/report") => ({ url, title: "Original report", snippet: "Measured 42%", publishedAt: "2026-11-01" });
const json = body => Response.json(body);

test("public URL policy rejects credentials, IP encodings, special hosts and non-web protocols", () => {
  for (const url of ["file:///etc/passwd", "ftp://example.com", "https://user:secret@example.com", "http://localhost/a",
    "http://127.0.0.1", "http://2130706433", "http://0x7f000001", "http://[::1]", "http://[::ffff:127.0.0.1]",
    "http://169.254.169.254/metadata", "https://foo.local", "https://foo.internal.", "https://example.com:8080"]) {
    assert.throws(() => publicWebUrl(url), /网页|公开|本机/);
  }
  assert.equal(publicWebUrl("https://EXAMPLE.COM/report#section"), "https://example.com/report");
});

test("search normalizes evidence, drops unsafe/duplicate URLs and retains a stable source ID on page read", async () => {
  let calls = 0;
  const service = new AgentWebResearchService({
    async search(input) { calls++; assert.equal(input.timeRange, "month"); return [source(), source(), source("http://127.0.0.1")]; },
    async read(url) { return { url, content: "A".repeat(15_000) }; },
  });
  const session = service.createSession();
  const args = { query: " recent report ", timeRange: "month" };
  const found = await session.search(args, signal());
  assert.equal(found.results.length, 1);
  assert.equal(found.evidenceType, "search_snippets");
  assert.equal(found.untrusted, true);
  assert.equal(found.results[0].publishedAt, "2026-11-01T00:00:00.000Z");
  assert.ok(Date.parse(found.results[0].retrievedAt));
  assert.deepEqual(await session.search(args, signal()), found);
  assert.equal(calls, 1);
  const read = await session.read({ url: source().url, maxChars: 1000 }, signal());
  assert.equal(read.results[0].sourceId, found.results[0].sourceId);
  assert.equal(read.results[0].title, "Original report");
  assert.equal(read.results[0].content.length, 1000);
  assert.equal(read.results[0].truncated, true);
  assert.equal(read.evidenceType, "page_content");
});

test("budgets include failed calls and reset for a new turn; cached reads do not consume budget", async () => {
  let searches = 0;
  let reads = 0;
  const service = new AgentWebResearchService({
    async search() { searches++; throw new Error("provider down"); },
    async read(url) { reads++; return { url, content: "body ".repeat(3000) }; },
  });
  const first = service.createSession();
  for (let index = 0; index < 3; index++) await assert.rejects(first.search({ query: String(index) }, signal()));
  await assert.rejects(first.search({ query: "fourth" }, signal()), { code: "WEB_SEARCH_BUDGET" });
  assert.equal(searches, 3);
  for (let index = 0; index < 4; index++) await first.read({ url: `https://example.com/${index}` }, signal());
  await first.read({ url: "https://example.com/0" }, signal());
  assert.equal(reads, 4);
  await assert.rejects(first.read({ url: "https://example.com/5" }, signal()), { code: "WEB_READ_BUDGET" });
  await service.createSession().read({ url: "https://example.com/5" }, signal());
  assert.equal(reads, 5);
  assert.equal(new AgentWebResearchService().available, false);
});

test("invalid arguments consume no provider requests and unknown publication dates stay unknown", async () => {
  let requests = 0;
  const session = new AgentWebResearchService({
    async search() { requests++; return [{ ...source(), publishedAt: "yesterday" }]; },
    async read() { requests++; return source(); },
  }).createSession();
  for (const args of [{ query: "" }, { query: "ok", limit: 9 }, { query: "ok", domains: ["localhost"] },
    { query: "ok", domains: ["example.com/path"] }, { query: "ok", timeRange: "forever" }]) {
    await assert.rejects(session.search(args, signal()));
  }
  await assert.rejects(session.read({ url: source().url, maxChars: 999 }, signal()));
  assert.equal(requests, 0);
  assert.equal((await session.search({ query: "ok" }, signal())).results[0].publishedAt, null);
});

test("Tavily requests use fixed endpoints and explicit low-cost options without fetching source URLs", async () => {
  const requests = [];
  const provider = new TavilyWebResearchMapper("private-test-key", async (url, init) => {
    requests.push({ url, body: JSON.parse(init.body), headers: new Headers(init.headers), redirect: init.redirect });
    return url.endsWith("search") ? json({ results: [{ url: source().url, title: "Report", content: "snippet" }] })
      : json({ results: [{ url: source().url, raw_content: "page body" }] });
  });
  await provider.search({ query: "report", limit: 3, timeRange: "week", domains: ["example.com"] }, signal());
  assert.equal((await provider.read(source().url, signal())).content, "page body");
  assert.deepEqual(requests.map(request => request.url), ["https://api.tavily.com/search", "https://api.tavily.com/extract"]);
  assert.equal(requests[0].headers.get("authorization"), "Bearer private-test-key");
  assert.equal(requests[0].body.search_depth, "basic");
  assert.equal(requests[0].body.include_answer, false);
  assert.equal(requests[0].body.time_range, "week");
  assert.deepEqual(requests[0].body.include_domains, ["example.com"]);
  assert.equal(requests[1].redirect, "manual");
});

test("default Tavily transport preserves the global fetch receiver required by Workers", async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async function (url, init) {
    assert.equal(this, globalThis, "Workers fetch rejects an instance as its receiver");
    assert.equal(init.redirect, "manual", "Workers only supports follow or manual redirects");
    requests.push(url);
    return url.endsWith("search") ? json({ results: [] })
      : json({ results: [{ url: source().url, raw_content: "page body" }] });
  };
  try {
    const provider = new TavilyWebResearchMapper("private-test-key");
    assert.deepEqual(await provider.search({ query: "report", limit: 1 }, signal()), []);
    assert.equal((await provider.read(source().url, signal())).content, "page body");
    assert.equal(requests.length, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Tavily redirects are rejected without following them or exposing the API key", async () => {
  let calls = 0;
  const provider = new TavilyWebResearchMapper("private-test-key", async (_url, init) => {
    calls++;
    assert.equal(init.redirect, "manual");
    return new Response(null, { status: 302, headers: { location: "https://other.example/" } });
  });
  await assert.rejects(provider.search({ query: "report", limit: 1 }, signal()), { code: "WEB_PROVIDER_UNAVAILABLE" });
  assert.equal(calls, 1);
});

test("provider errors never disclose bodies/keys, oversized responses fail and timeouts are bounded", async () => {
  const args = { query: "report", limit: 3 };
  for (const response of [new Response("private-test-key", { status: 401 }),
    json({ results: [], secret: "x".repeat(513_000) }), new Response("private-test-key", { headers: { "content-type": "text/html" } })]) {
    const provider = new TavilyWebResearchMapper("private-test-key", async () => response);
    await assert.rejects(provider.search(args, signal()), error => !error.message.includes("private-test-key"));
  }
  const provider = new TavilyWebResearchMapper("private-test-key", async (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener("abort", () => reject(new Error("private-test-key")), { once: true });
  }), 10);
  await assert.rejects(provider.search(args, signal()), { code: "WEB_TIMEOUT" });
});

test("tool events pair by invocation and both tools are read-only; failures stay recoverable", async () => {
  const events = [];
  const session = new AgentWebResearchService({ async search() { return [source()]; }, async read() { throw new Error("secret upstream failure"); } }).createSession();
  const tools = createWebResearchTools(session, async (event, data) => events.push({ event, data }));
  assert.ok([...tools.values()].every(tool => tool.definition().readOnly && SUBTASK_READ_ONLY_TOOLS.has(tool.definition().name)));
  const pipeline = new AgentToolExecutionPipeline(tools);
  const context = { userId: 42, conversationId: 7, runVersion: 1, invocationId: "search-1", idempotencyKey: "search-1", signal: signal() };
  assert.equal((await pipeline.execute({ name: "search_web", arguments: { query: "report" } }, context)).ok, true);
  const failed = await pipeline.execute({ name: "read_webpage", arguments: { url: source().url } }, { ...context, invocationId: "read-1" });
  assert.equal(failed.ok, false);
  assert.equal(failed.data.errorCode, "WEB_PROVIDER_UNAVAILABLE");
  assert.ok(!JSON.stringify(events).includes("secret upstream failure"));
  assert.deepEqual(events.filter(item => item.event === "tool_result").map(item => item.data.invocationId), ["search-1", "read-1"]);
});

test("cancellation propagates to extraction and cannot publish a successful source card", async () => {
  const controller = new AbortController();
  const events = [];
  const mapper = new TavilyWebResearchMapper("test", async (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
    controller.abort("user_cancel");
  }));
  const tools = createWebResearchTools(new AgentWebResearchService(mapper).createSession(), async (event, data) => events.push({ event, data }));
  await assert.rejects(new AgentToolExecutionPipeline(tools).execute({ name: "read_webpage", arguments: { url: source().url } }, {
    userId: 42, conversationId: 7, runVersion: 1, invocationId: "cancelled", idempotencyKey: "cancelled", signal: controller.signal,
  }));
  assert.equal(events.some(item => item.event === "tool_result"), false);
  assert.ok(events.some(item => item.event === "tool_error" && item.data.errorCode === "WEB_CANCELLED"));
});
