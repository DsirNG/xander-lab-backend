import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { AgentApprovalError, AgentToolApprovalService } from "../services/agent-tool-approval-service.js";

type UserResolver = () => Promise<number | Response>;
const MAX_BODY_BYTES = 8 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function positiveId(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

async function readDecision(request: Request): Promise<{
  approved: boolean; reason: string | null; location: { latitude: number; longitude: number } | null;
} | Response> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_BODY_BYTES)) {
    return apiError(413, "请求内容过大");
  }
  let text = "";
  try {
    const reader = request.body?.getReader();
    if (!reader) return apiError(400, "请求内容格式不正确");
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let total = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > MAX_BODY_BYTES) {
          await reader.cancel();
          return apiError(413, "请求内容过大");
        }
        text += decoder.decode(value, { stream: true });
      }
      text += decoder.decode();
    } finally {
      reader.releaseLock();
    }
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
  let body: unknown;
  try {
    body = JSON.parse(text) as unknown;
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return apiError(400, "请求内容格式不正确");
  const row = body as Record<string, unknown>;
  if (typeof row.approved !== "boolean"
    || (row.reason !== undefined && row.reason !== null
      && (typeof row.reason !== "string" || row.reason.length > 500))) {
    return apiError(400, "审批请求参数不正确");
  }
  let location: { latitude: number; longitude: number } | null = null;
  if (row.location !== undefined && row.location !== null) {
    if (!row.approved || !row.location || typeof row.location !== "object" || Array.isArray(row.location)) {
      return apiError(400, "定位授权结果格式不正确");
    }
    const coordinates = row.location as Record<string, unknown>;
    if (Object.keys(coordinates).some((key) => key !== "latitude" && key !== "longitude")
      || typeof coordinates.latitude !== "number" || !Number.isFinite(coordinates.latitude)
      || coordinates.latitude < -90 || coordinates.latitude > 90
      || typeof coordinates.longitude !== "number" || !Number.isFinite(coordinates.longitude)
      || coordinates.longitude < -180 || coordinates.longitude > 180) {
      return apiError(400, "定位授权结果格式不正确");
    }
    location = { latitude: coordinates.latitude, longitude: coordinates.longitude };
  }
  return { approved: row.approved, reason: typeof row.reason === "string" ? row.reason : null, location };
}

/** Maps the owner-authenticated Agent approval endpoints onto the approval use case. */
export async function handleAgentApprovalRequest(
  request: Request,
  service: AgentToolApprovalService,
  resolveUser: UserResolver,
): Promise<Response | null> {
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/api\/agent\/conversations\/(\d+)\/approvals(?:\/(\d+)\/decision)?$/);
  if (!match) return null;
  const conversationId = positiveId(match[1]);
  const approvalId = match[2] ? positiveId(match[2]) : null;
  if (!conversationId || (match[2] && !approvalId)) return apiError(400, "会话或审批 ID 无效");
  const isDecision = Boolean(match[2]);
  if ((!isDecision && request.method !== "GET") || (isDecision && request.method !== "POST")) return null;

  const userId = await resolveUser();
  if (userId instanceof Response) return userId;
  try {
    if (!isDecision) {
      const approvals = await service.list(userId, conversationId);
      return approvals ? success(approvals) : apiError(404, "会话不存在");
    }
    const decision = await readDecision(request);
    if (decision instanceof Response) return decision;
    return success(await service.decide(
      userId, conversationId, approvalId!, decision.approved, decision.reason, decision.location,
    ));
  } catch (error) {
    if (error instanceof AgentApprovalError) return apiError(error.status, error.message);
    return apiError(503, "智能体服务暂不可用");
  }
}
