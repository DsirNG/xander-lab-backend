import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { ContentAgentBlogAgentMapper } from "../.test-build/mappers/content-agent-blog-agent-mapper.js";

afterEach(() => mock.restoreAll());

test("BlogAgent stream forwards a stage before the upstream stream completes", async () => {
  const encoder = new TextEncoder();
  let releaseUpstream = () => {};
  let markStageSeen = () => {};
  const stageSeen = new Promise((resolve) => { markStageSeen = resolve; });
  const stageFrame = encoder.encode("event: stage\r\ndata: research|正在联网搜索相关资料…\r\n\r\n");
  const response = new Response(new ReadableStream({
    async start(controller) {
      const splitAt = stageFrame.byteLength - 2;
      controller.enqueue(stageFrame.slice(0, splitAt));
      controller.enqueue(stageFrame.slice(splitAt));
      await new Promise((resolve) => { releaseUpstream = resolve; });
      controller.enqueue(encoder.encode(`event: complete\ndata: ${JSON.stringify({
        task: { id: 7, status: "ready", title: "A title", summary: "A summary", publishedPostId: null },
      })}\n\n`));
      controller.close();
    },
  }), { headers: { "content-type": "text/event-stream; charset=utf-8" } });
  mock.method(globalThis, "fetch", async () => response);

  const mapper = new ContentAgentBlogAgentMapper({
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.example",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "service-token",
  });
  const stages = [];
  const running = mapper.runStream(42, 7, new AbortController().signal, async (stage, message) => {
    stages.push({ stage, message });
    markStageSeen();
  });
  let forwardedBeforeCompletion = false;
  try {
    forwardedBeforeCompletion = await Promise.race([
      stageSeen.then(() => true),
      new Promise((resolve) => setTimeout(() => resolve(false), 100)),
    ]);
  } finally {
    releaseUpstream();
  }

  const task = await running;
  assert.equal(forwardedBeforeCompletion, true);
  assert.deepEqual(stages, [{ stage: "research", message: "正在联网搜索相关资料…" }]);
  assert.equal(task.status, "ready");
  assert.equal(task.id, 7);
});
