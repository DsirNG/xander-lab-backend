import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { ContentNotificationService } from "../services/content-notification-service.js";

const USER_ID = /^[1-9]\d{0,17}$/;
const MAX_BODY_BYTES = 8 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function parsePage(raw: string | null, fallback: number): number | null {
  if (raw === null) return fallback;
  if (!/^\d+$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= 1 ? value : null;
}

function parseCursor(raw: string | null): number | null {
  if (raw === null) return 0;
  if (!/^\d+$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function userId(request: Request): number | null {
  const raw = request.headers.get("x-user-id")?.trim() ?? "";
  if (!USER_ID.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : null;
}

async function readBody(request: Request): Promise<Record<string, unknown> | null> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_BODY_BYTES)) return null;
  if (!request.body) return null;
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function notificationEvent(record: unknown): string {
  const row = record as { id: number };
  return `id: ${row.id}\nevent: notification\ndata: ${JSON.stringify(record)}\n\n`;
}

function eventStream(request: Request, service: ContentNotificationService, ownerId: number): Response {
  const url = new URL(request.url);
  const cursor = parseCursor(request.headers.get("last-event-id") ?? url.searchParams.get("afterId"));
  if (cursor === null) return apiError(400, "通知游标无效");
  let lastId = cursor;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let closed = false;
  let cleanupStarted = false;
  let lastBroadcastId = 0;
  let lastHeartbeatAt = 0;
  const connectionId = crypto.randomUUID();
  const encoder = new TextEncoder();
  let controllerRef: ReadableStreamDefaultController<Uint8Array> | null = null;
  const cleanup = () => {
    if (cleanupStarted) return;
    cleanupStarted = true;
    void service.closeStream(connectionId).catch(() => undefined);
  };
  const abort = () => {
    closed = true;
    if (timer !== undefined) clearTimeout(timer);
    try { controllerRef?.close(); } catch { /* stream already closed */ }
    cleanup();
  };
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controllerRef = controller;
      request.signal.addEventListener("abort", abort, { once: true });
      const poll = async (): Promise<void> => {
        if (closed) return;
        try {
          const now = Date.now();
          if (now - lastHeartbeatAt >= 15_000) {
            await service.heartbeatStream(connectionId, now);
            lastHeartbeatAt = now;
          }
          const records = await service.listAfter(ownerId, lastId);
          for (const record of records) {
            if (closed) return;
            lastId = Math.max(lastId, record.id);
            controller.enqueue(encoder.encode(notificationEvent(record)));
          }
          const broadcasts = await service.listBroadcastsAfter(lastBroadcastId, Date.now());
          for (const broadcast of broadcasts) {
            if (closed) return;
            lastBroadcastId = Math.max(lastBroadcastId, broadcast.id);
            controller.enqueue(encoder.encode(`event: notification\ndata: ${JSON.stringify({
              type: broadcast.type,
              title: broadcast.title,
              message: broadcast.message,
              planId: "",
              runId: "",
            })}\n\n`));
          }
          if (records.length === 0 && broadcasts.length === 0) controller.enqueue(encoder.encode(": keep-alive\n\n"));
        } catch {
          if (!closed) {
            try {
              controller.enqueue(encoder.encode("event: error\ndata: {\"message\":\"notification stream unavailable\"}\n\n"));
            } catch { /* connection closed while polling */ }
            abort();
            return;
          }
        }
        if (!closed) timer = setTimeout(() => { void poll(); }, 5_000);
      };
      void (async () => {
        try {
          lastBroadcastId = await service.openStream(connectionId, ownerId, Date.now());
          if (closed) {
            cleanup();
            return;
          }
          lastHeartbeatAt = Date.now();
          controller.enqueue(encoder.encode(": connected\n\n"));
          await poll();
        } catch {
          if (!closed) {
            try {
              controller.enqueue(encoder.encode("event: error\ndata: {\"message\":\"notification stream unavailable\"}\n\n"));
            } catch { /* connection closed while registering */ }
            abort();
          }
        }
      })();
    },
    cancel() {
      abort();
      request.signal.removeEventListener("abort", abort);
    },
  });
  return new Response(stream, {
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-store, must-revalidate",
      "x-accel-buffering": "no",
    },
  });
}

export async function handleContentNotificationRequest(
  request: Request,
  service: ContentNotificationService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method === "POST" && url.pathname === "/internal/content/notifications/test-broadcast") {
    try {
      return jsonResponse({ recipients: await service.broadcastTest() });
    } catch {
      return apiError(503, "通知广播暂不可用");
    }
  }
  const internalCreate = request.method === "POST" && url.pathname === "/internal/content/notifications";
  if (internalCreate) {
    const body = await readBody(request);
    if (!body) return apiError(400, "通知请求格式不正确");
    const ownerId = typeof body.userId === "number" && Number.isSafeInteger(body.userId) && body.userId > 0
      ? body.userId : null;
    const type = typeof body.type === "string" ? body.type.trim() : "";
    const title = typeof body.title === "string" ? body.title.trim() : "";
    const message = typeof body.message === "string" ? body.message.trim() : "";
    if (ownerId === null || !type || type.length > 48 || !title || title.length > 200
      || !message || message.length > 1000) return apiError(400, "通知请求格式不正确");
    const optionalId = (value: unknown) => value === undefined || value === null
      ? null : (typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : NaN);
    const planId = optionalId(body.planId);
    const runId = optionalId(body.runId);
    const resourceId = optionalId(body.resourceId);
    const resourceType = body.resourceType === undefined || body.resourceType === null
      ? null : (typeof body.resourceType === "string" ? body.resourceType.trim() : "");
    if ([planId, runId, resourceId].some(Number.isNaN)
      || resourceType !== null && (!resourceType || resourceType.length > 32)) {
      return apiError(400, "通知请求格式不正确");
    }
    try {
      const id = await service.create({
        userId: ownerId,
        type,
        title,
        message,
        planId,
        runId,
        resourceType,
        resourceId,
      });
      return jsonResponse({ id });
    } catch {
      return apiError(503, "通知保存暂不可用");
    }
  }

  if (!url.pathname.startsWith("/api/notifications")) return null;
  const ownerId = userId(request);
  if (ownerId === null) return apiError(401, "未登录或登录已过期");

  try {
    if (request.method === "GET" && url.pathname === "/api/notifications/events") {
      return eventStream(request, service, ownerId);
    }
    if (request.method === "GET" && url.pathname === "/api/notifications/unread-count") {
      return success(await service.unreadCount(ownerId));
    }
    if (request.method === "GET" && url.pathname === "/api/notifications") {
      const page = parsePage(url.searchParams.get("page"), 1);
      const size = parsePage(url.searchParams.get("size"), 10);
      if (page === null || size === null) return apiError(400, "分页参数无效");
      return success(await service.list(ownerId, page, size));
    }
    if (request.method === "PATCH" && url.pathname === "/api/notifications/read-all") {
      await service.markAllRead(ownerId);
      return success(null);
    }
    const match = url.pathname.match(/^\/api\/notifications\/([1-9]\d{0,17})\/read$/);
    if (request.method === "PATCH" && match) {
      const id = Number(match[1]);
      if (!Number.isSafeInteger(id)) return apiError(400, "通知 ID 无效");
      return await service.markRead(ownerId, id) ? success(null) : apiError(404, "通知不存在");
    }
    return null;
  } catch {
    return apiError(503, "通知服务暂不可用");
  }
}
