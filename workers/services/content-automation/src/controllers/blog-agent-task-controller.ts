import { apiError, jsonResponse, type WorkerExecutionContextLike } from "@dinqor/worker-runtime";
import type { ContentEnvironment } from "../interfaces/content-environment.js";
import { BlogAgentTaskError, BlogAgentTaskService } from "../services/blog-agent-task-service.js";

const BASE = "/api/blog-agent/tasks";
const INTERNAL_BASE = "/internal/agent/blogagent";
const MAX_BODY_BYTES = 64 * 1024;
const encoder = new TextEncoder();

function success(data: unknown): Response { return jsonResponse({ code: 200, message: "success", data }); }

function positive(raw: string | undefined): number | null {
  if (!raw || !/^[1-9]\d{0,17}$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : null;
}

function userId(request: Request): number | null {
  return positive(request.headers.get("x-user-id")?.trim());
}

async function readBody(request: Request): Promise<Record<string, unknown> | Response> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_BODY_BYTES)) {
    return apiError(413, "博客生成助手请求过大");
  }
  const reader = request.body?.getReader();
  if (!reader) return apiError(400, "请求内容格式不正确");
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) { await reader.cancel(); return apiError(413, "博客生成助手请求过大"); }
      chunks.push(value);
    }
  } catch { return apiError(400, "请求内容格式不正确"); }
  finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown> : apiError(400, "请求内容格式不正确");
  } catch { return apiError(400, "请求内容格式不正确"); }
}

function internalAuthorized(request: Request, env: ContentEnvironment): boolean {
  const supplied = request.headers.get("x-dinqor-service-token")
    ?? request.headers.get("x-dinqor-internal-token");
  return Boolean(env.INTERNAL_SERVICE_TOKEN && supplied && constantTimeEqual(supplied, env.INTERNAL_SERVICE_TOKEN));
}

function constantTimeEqual(left: string, right: string): boolean {
  const a = encoder.encode(left);
  const b = encoder.encode(right);
  let diff = a.length ^ b.length;
  const length = Math.max(a.length, b.length);
  for (let i = 0; i < length; i += 1) diff |= (a[i % Math.max(1, a.length)] ?? 0) ^ (b[i % Math.max(1, b.length)] ?? 0);
  return diff === 0;
}

function error(errorValue: unknown): Response {
  return errorValue instanceof BlogAgentTaskError ? apiError(errorValue.status, errorValue.message)
    : apiError(503, "博客生成助手服务暂不可用");
}

function eventChunk(type: string, data: string, id?: number): Uint8Array {
  const eventId = id === undefined ? "" : `id: ${id}\n`;
  const safeData = data.split(/\r?\n/).map((line) => `data: ${line}`).join("\n");
  return encoder.encode(`${eventId}event: ${type}\n${safeData}\n\n`);
}

function sse(run: (send: (id: number, type: string, data: string) => void) => Promise<unknown>, context?: WorkerExecutionContextLike): Response {
  let controllerRef: ReadableStreamDefaultController<Uint8Array> | null = null;
  let closed = false;
  const send = (id: number, type: string, data: string) => {
    if (closed || !controllerRef) return;
    try { controllerRef.enqueue(eventChunk(type, data, id)); } catch { /* Client disconnected; durable D1 events remain replayable. */ }
  };
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controllerRef = controller;
      controller.enqueue(encoder.encode("retry: 2000\n\n"));
      const work = run(send).catch((failure) => {
        if (!closed) {
          try { controller.enqueue(eventChunk("error", failure instanceof Error ? failure.message : "博客生成助手执行失败")); }
          catch { /* The response may already be closed. */ }
        }
      }).finally(() => {
        closed = true;
        try { controller.close(); } catch { /* Already cancelled. */ }
      });
      if (context) context.waitUntil(work);
    },
    cancel() { closed = true; },
  });
  return new Response(body, { headers: {
    "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-cache, no-transform",
    "x-accel-buffering": "no",
  } });
}

async function streamReplay(
  user: number, id: number, cursor: number, service: BlogAgentTaskService,
): Promise<Response> {
  let closed = false;
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      const work = async () => {
        let current = cursor;
        let lastHeartbeat = Date.now();
        const deadline = Date.now() + 15 * 60_000;
        while (!closed && Date.now() < deadline) {
          const events = await service.listEventsAfter(id, current, 100);
          for (const event of events) {
            current = event.id;
            controller.enqueue(eventChunk(event.eventType, event.dataContent, event.id));
          }
          const snapshot = await service.detail(user, id);
          if (["ready", "failed"].includes(snapshot.task.status)) {
            if (!events.some((item) => item.eventType === "complete")) {
              controller.enqueue(eventChunk("complete", JSON.stringify(snapshot)));
            }
            break;
          }
          if (Date.now() - lastHeartbeat >= 15_000) {
            controller.enqueue(encoder.encode(": heartbeat\n\n"));
            lastHeartbeat = Date.now();
          }
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
      };
      void work().catch((failure) => {
        if (!closed) controller.enqueue(eventChunk("error", failure instanceof Error ? failure.message : "博客生成助手事件读取失败"));
      }).finally(() => {
        closed = true;
        try { controller.close(); } catch { /* Already cancelled. */ }
      });
    },
    cancel() { closed = true; },
  });
  return new Response(body, { headers: {
    "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-cache, no-transform",
    "x-accel-buffering": "no",
  } });
}

export async function handleBlogAgentTaskRequest(
  request: Request,
  env: ContentEnvironment,
  service: BlogAgentTaskService,
  context?: WorkerExecutionContextLike,
): Promise<Response | null> {
  const { pathname, searchParams } = new URL(request.url);

  if (pathname.startsWith(`${INTERNAL_BASE}/`) || pathname === `${INTERNAL_BASE}/create`
    || pathname === `${INTERNAL_BASE}/find-ready` || pathname === `${INTERNAL_BASE}/find-published`) {
    if (!internalAuthorized(request, env)) return apiError(404, "Not Found");
    if (request.method !== "POST") return apiError(404, "Not Found");
    const body = await readBody(request);
    if (body instanceof Response) return body;
    const owner = positive(String(body.userId ?? ""));
    if (!owner) return apiError(400, "博客生成助手请求参数非法");
    try {
      if (pathname === `${INTERNAL_BASE}/create`) {
        const task = await service.create(owner, body.input, body.clientRequestId, body.mode);
        return jsonResponse(task);
      }
      if (pathname === `${INTERNAL_BASE}/find-ready`) return jsonResponse(await service.findReadyByClientRequestId(owner, body.clientRequestId));
      if (pathname === `${INTERNAL_BASE}/find-published`) {
        const taskId = positive(String(body.taskId ?? ""));
        if (!taskId) return apiError(400, "博客生成助手请求参数非法");
        return jsonResponse(await service.findPublishedPost(owner, taskId));
      }
      const run = pathname.match(/^\/internal\/agent\/blogagent\/([1-9]\d{0,17})\/run-stream$/);
      if (run) {
        const taskId = positive(run[1]);
        if (!taskId) return apiError(400, "博客生成助手请求参数非法");
        await service.prepare(owner, taskId, "initial");
        return sse((send) => service.runClaimed(owner, taskId, "initial", null,
          (eventId, type, data) => send(eventId, type, data)), context);
      }
      const publish = pathname.match(/^\/internal\/agent\/blogagent\/([1-9]\d{0,17})\/publish$/);
      if (publish) {
        const taskId = positive(publish[1]);
        if (!taskId) return apiError(400, "博客生成助手请求参数非法");
        return jsonResponse(await service.publish(owner, taskId));
      }
      return apiError(404, "Not Found");
    } catch (failure) { return error(failure); }
  }

  if (!pathname.startsWith(`${BASE}/`) && pathname !== BASE) return null;
  const owner = userId(request);
  if (owner === null) return apiError(401, "未登录或登录已过期");
  try {
    if (pathname === BASE && request.method === "GET") return success(await service.list(owner));
    if (pathname === BASE && request.method === "POST") {
      const body = await readBody(request);
      if (body instanceof Response) return body;
      return success(await service.create(owner, body.input, body.clientRequestId, body.mode));
    }
    const match = pathname.match(/^\/api\/blog-agent\/tasks\/([1-9]\d{0,17})(?:\/(run|run\/stream|publish|events|messages\/stream))?$/);
    if (!match) return null;
    const taskId = positive(match[1]);
    if (!taskId) return apiError(400, "博客生成助手任务 ID 无效");
    const action = match[2];
    if (!action && request.method === "GET") return success(await service.detail(owner, taskId));
    if (action === "run" && request.method === "POST") return success(await service.run(owner, taskId));
    if (action === "run/stream" && request.method === "POST") {
      await service.prepare(owner, taskId, "initial");
      return sse((send) => service.runClaimed(owner, taskId, "initial", null,
        (eventId, type, data) => send(eventId, type, data)), context);
    }
    if (action === "messages/stream" && request.method === "POST") {
      const body = await readBody(request);
      if (body instanceof Response) return body;
      if (typeof body.content !== "string" || !body.content.trim()) return apiError(400, "修改要求不能为空");
      await service.prepare(owner, taskId, "revision");
      return sse((send) => service.runClaimed(owner, taskId, "revision", String(body.content),
        (eventId, type, data) => send(eventId, type, data)), context);
    }
    if (action === "publish" && request.method === "POST") return success(await service.publish(owner, taskId));
    if (action === "events" && request.method === "GET") {
      const raw = request.headers.get("last-event-id") ?? searchParams.get("afterEventId") ?? "0";
      const cursor = /^\d+$/.test(raw) && Number.isSafeInteger(Number(raw)) ? Number(raw) : 0;
      await service.detail(owner, taskId);
      return streamReplay(owner, taskId, cursor, service);
    }
    return null;
  } catch (failure) { return error(failure); }
}
