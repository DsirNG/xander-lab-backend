import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { AgentConversationService } from "../services/agent-conversation-service.js";

type UserResolver = () => Promise<number | Response>;
type JsonObject = Record<string, unknown>;
const MAX_REQUEST_BYTES = 32 * 1024;
const MAX_MESSAGE_LENGTH = 4000;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

async function readJsonBody(request: Request): Promise<JsonObject | Response> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_REQUEST_BYTES) return apiError(413, "请求内容过大");
  if (!request.body) return apiError(400, "请求内容格式不正确");
  let value: unknown;
  try {
    value = await request.json();
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return apiError(400, "请求内容格式不正确");
  return value as JsonObject;
}

function positiveId(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

export async function handleAgentConversationRequest(
  request: Request,
  service: AgentConversationService,
  resolveUser: UserResolver,
): Promise<Response | null> {
  const url = new URL(request.url);
  const basePath = "/api/agent/conversations";

  if (request.method === "GET") {
    const sharedMatch = url.pathname.match(/^\/api\/agent\/conversations\/shared\/([^/]+)$/);
    if (sharedMatch) {
      try {
        const result = await service.getShared(sharedMatch[1]);
        return result ? success(result) : apiError(404, "分享链接无效或已失效");
      } catch {
        return apiError(503, "智能体服务暂不可用");
      }
    }
  }

  const collectionRoute = url.pathname === basePath;
  const itemMatch = url.pathname.match(/^\/api\/agent\/conversations\/(\d+)(?:\/(messages|share|pin|read))?$/);
  if (!collectionRoute && !(request.method === "POST" && url.pathname === basePath) && !itemMatch) return null;
  const userId = await resolveUser();
  if (userId instanceof Response) return userId;

  try {
    if (request.method === "GET" && collectionRoute) return success(await service.list(userId));
    if (request.method === "POST" && collectionRoute) {
      const body = await readJsonBody(request);
      if (body instanceof Response) return body;
      if (body.content !== undefined && body.content !== null && typeof body.content !== "string") {
        return apiError(400, "请求内容格式不正确");
      }
      const content = typeof body.content === "string" ? body.content.trim() : "";
      if (content.length > MAX_MESSAGE_LENGTH) return apiError(400, "消息内容不能超过 4000 个字符");
      if (body.attachments !== undefined && body.attachments !== null
        && (!Array.isArray(body.attachments) || body.attachments.length > 5)) {
        return apiError(400, "附件信息无效");
      }
      if (!content && (!Array.isArray(body.attachments) || body.attachments.length === 0)) {
        return apiError(400, "消息内容和附件不能同时为空");
      }
      const created = await service.create(userId, content);
      return created ? success(created) : apiError(503, "智能体服务暂不可用");
    }

    if (!itemMatch) return null;
    const conversationId = positiveId(itemMatch[1]);
    if (!conversationId) return apiError(400, "会话 ID 无效");
    const action = itemMatch[2];
    if (request.method === "GET" && !action) {
      const result = await service.get(userId, conversationId);
      return result ? success(result) : apiError(404, "会话不存在");
    }
    if (request.method === "GET" && action === "messages") {
      const result = await service.listMessages(userId, conversationId);
      return result ? success(result) : apiError(404, "会话不存在");
    }
    if (request.method === "POST" && action === "read") {
      const row = await service.markRead(userId, conversationId);
      return row ? success(row) : apiError(404, "会话不存在");
    }
    if (request.method === "POST" && action === "pin") {
      const row = await service.setPinned(userId, conversationId, true);
      return row ? success(row) : apiError(404, "会话不存在");
    }
    if (request.method === "DELETE" && action === "pin") {
      const row = await service.setPinned(userId, conversationId, false);
      return row ? success(row) : apiError(404, "会话不存在");
    }
    if (request.method === "POST" && action === "share") {
      const shareToken = await service.enableShare(userId, conversationId);
      if (shareToken === undefined) return apiError(404, "会话不存在");
      return shareToken ? success(shareToken) : apiError(503, "智能体服务暂不可用");
    }
    if (request.method === "DELETE" && action === "share") {
      return await service.disableShare(userId, conversationId) ? success(null) : apiError(404, "会话不存在");
    }
    return null;
  } catch {
    return apiError(503, "智能体服务暂不可用");
  }
}
