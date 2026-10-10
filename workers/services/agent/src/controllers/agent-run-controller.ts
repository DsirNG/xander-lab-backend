import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { AgentRunService, ResponseError } from "../services/agent-run-service.js";
import type { WorkerExecutionContextLike } from "@dinqor/worker-runtime";

type UserResolver = () => Promise<number | Response>;

interface TurnRequest {
  content: string;
  attachments: unknown[];
  deepThinking: boolean;
}

const MAX_REQUEST_BYTES = 32 * 1024;
const MAX_MESSAGE_LENGTH = 4000;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function conversationResponse(row: Record<string, unknown>) {
  const completedAt = typeof row.lastCompletedAt === "string" ? Date.parse(row.lastCompletedAt) : NaN;
  const readAt = typeof row.lastReadAt === "string" ? Date.parse(row.lastReadAt) : NaN;
  return {
    ...row,
    unread: Number.isFinite(completedAt) && (!Number.isFinite(readAt) || readAt < completedAt),
  };
}

async function readTurnRequest(request: Request): Promise<TurnRequest | Response> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_REQUEST_BYTES) return apiError(413, "请求内容过大");
  let value: unknown;
  try {
    value = await request.json();
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return apiError(400, "请求内容格式不正确");
  const body = value as Record<string, unknown>;
  if (body.content !== undefined && body.content !== null && typeof body.content !== "string") {
    return apiError(400, "请求内容格式不正确");
  }
  const content = typeof body.content === "string" ? body.content.trim() : "";
  if (content.length > MAX_MESSAGE_LENGTH) return apiError(400, "消息内容不能超过 4000 个字符");
  if (body.deepThinking !== undefined && typeof body.deepThinking !== "boolean") {
    return apiError(400, "请求内容格式不正确");
  }
  if (body.attachments !== undefined && body.attachments !== null
    && (!Array.isArray(body.attachments) || body.attachments.length > 5)) {
    return apiError(400, "附件信息无效");
  }
  const attachments = Array.isArray(body.attachments) ? body.attachments : [];
  if (!content && attachments.length === 0) return apiError(400, "消息内容和附件不能同时为空");
  return { content, attachments, deepThinking: body.deepThinking === true };
}

function sseFrame(id: number | null, eventType: string, data: unknown): string {
  const idLine = id === null ? "" : `id: ${id}\n`;
  const body = typeof data === "string" ? data : JSON.stringify(data);
  const dataLines = body.split(/\r?\n/).map((line) => `data: ${line}`).join("\n");
  return `${idLine}event: ${eventType}\n${dataLines}\n\n`;
}

function eventData(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function streamResponse(
  run: (sink: (id: number, eventType: string, data: unknown) => void) => Promise<unknown>,
  context?: WorkerExecutionContextLike,
): Response {
  let open = true;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const encoder = new TextEncoder();
      const execution = run((id, eventType, data) => {
        if (open) controller.enqueue(encoder.encode(sseFrame(id, eventType, data)));
      }).catch(() => undefined).finally(() => {
        if (open) {
          open = false;
          controller.close();
        }
      });
      context?.waitUntil(execution);
    },
    cancel() {
      open = false;
    },
  });
  return new Response(stream, {
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-transform",
      "x-accel-buffering": "no",
    },
  });
}

function positiveId(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

export async function handleAgentRunRequest(
  request: Request,
  service: AgentRunService,
  resolveUser: UserResolver,
  context?: WorkerExecutionContextLike,
): Promise<Response | null> {
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/api\/agent\/conversations\/(\d+)\/(messages(?:\/stream)?|events|cancel)$/);
  if (!match) return null;
  const conversationId = positiveId(match[1]);
  if (!conversationId) return apiError(400, "会话 ID 无效");
  const action = match[2];
  const allowed = (action === "messages" && request.method === "POST")
    || (action === "messages/stream" && request.method === "POST")
    || (action === "events" && request.method === "GET")
    || (action === "cancel" && request.method === "POST");
  if (!allowed) return null;

  const userId = await resolveUser();
  if (userId instanceof Response) return userId;

  try {
    if (action === "cancel") {
      const row = await service.cancelTurn(userId, conversationId);
      return row ? success(conversationResponse(row as unknown as Record<string, unknown>))
        : apiError(404, "会话不存在");
    }

    if (action === "events") {
      const conversation = await service.getConversation(userId, conversationId);
      if (!conversation) return apiError(404, "会话不存在");
      const headerCursor = request.headers.get("last-event-id");
      const queryCursor = url.searchParams.get("afterEventId");
      const cursorText = headerCursor?.trim() || queryCursor || "0";
      const cursor = /^\d+$/.test(cursorText) ? Number(cursorText) : 0;
      let open = true;
      const stream = new ReadableStream<Uint8Array>({
        async start(controller) {
          const encoder = new TextEncoder();
          let lastHeartbeat = Date.now();
          let nextId = Number.isSafeInteger(cursor) ? cursor : 0;
          try {
            while (open) {
              const events = await service.listEventsAfter(conversationId, conversation.runVersion, nextId);
              for (const event of events) {
                nextId = event.id;
                controller.enqueue(encoder.encode(sseFrame(event.id, event.eventType, eventData(event.dataContent))));
              }
              const current = await service.getConversation(userId, conversationId);
              if (!current || current.runVersion !== conversation.runVersion || current.status !== "running") break;
              if (Date.now() - lastHeartbeat >= 15_000) {
                controller.enqueue(encoder.encode(": heartbeat\n\n"));
                lastHeartbeat = Date.now();
              }
              await new Promise((resolve) => setTimeout(resolve, 500));
            }
          } catch {
            // The durable event log remains available to a later reconnect.
          } finally {
            if (open) {
              open = false;
              controller.close();
            }
          }
        },
        cancel() {
          open = false;
        },
      });
      return new Response(stream, {
        headers: {
          "content-type": "text/event-stream; charset=utf-8",
          "cache-control": "no-cache, no-transform",
          "x-accel-buffering": "no",
        },
      });
    }

    const turnRequest = await readTurnRequest(request);
    if (turnRequest instanceof Response) return turnRequest;
    const content = turnRequest.content || "请分析我上传的附件。";
    const runVersion = await service.claimTurn(userId, conversationId, content, turnRequest.attachments);
    if (action === "messages/stream") {
      return streamResponse(async (sink) => service.executeTurn(
        userId, conversationId, runVersion, sink, turnRequest.deepThinking,
      ), context);
    }

    let failure: string | null = null;
    const result = await service.executeTurn(userId, conversationId, runVersion, (_id, eventType, data) => {
      if (eventType === "error" && typeof data === "string") failure = data;
    }, turnRequest.deepThinking);
    return result.ok ? success(runVersion) : apiError(503, failure || "智能体执行失败，请稍后重试");
  } catch (error) {
    if (error instanceof ResponseError) return apiError(error.status, error.message);
    return apiError(503, "智能体服务暂不可用");
  }
}
