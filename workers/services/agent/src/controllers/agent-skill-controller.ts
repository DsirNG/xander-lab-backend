import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { AgentSkillService, AgentSkillValidationError, type AgentSkillInput } from "../services/agent-skill-service.js";

type UserResolver = () => Promise<number | Response>;
const MAX_BODY_BYTES = 64 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function positiveId(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

async function readBodyWithinLimit(request: Request): Promise<string | Response> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
  if (!request.body) return apiError(400, "请求内容格式不正确");

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > MAX_BODY_BYTES) {
        await reader.cancel();
        return apiError(413, "请求内容过大");
      }
      chunks.push(value);
    }
  } catch {
    return apiError(400, "请求内容格式不正确");
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

async function readSkillInput(request: Request): Promise<AgentSkillInput | Response> {
  let value: unknown;
  try {
    const text = await readBodyWithinLimit(request);
    if (text instanceof Response) return text;
    value = JSON.parse(text);
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return apiError(400, "请求内容格式不正确");
  const body = value as Record<string, unknown>;
  if (typeof body.skillKey !== "string" || typeof body.name !== "string"
    || typeof body.description !== "string" || typeof body.instructions !== "string") {
    return apiError(400, "请求内容格式不正确");
  }
  if (body.toolNames !== undefined && body.toolNames !== null
    && (!Array.isArray(body.toolNames) || body.toolNames.some((item) => typeof item !== "string"))) {
    return apiError(400, "Skill 工具列表格式不正确");
  }
  return {
    skillKey: body.skillKey,
    name: body.name,
    description: body.description,
    instructions: body.instructions,
    toolNames: Array.isArray(body.toolNames) ? body.toolNames as string[] : [],
  };
}

/** Maps the authenticated Agent Skill HTTP contract onto the use-case service. */
export async function handleAgentSkillRequest(
  request: Request,
  service: AgentSkillService,
  resolveUser: UserResolver,
): Promise<Response | null> {
  const url = new URL(request.url);
  const basePath = "/api/agent/skills";
  const isCollection = url.pathname === basePath;
  const isTools = url.pathname === `${basePath}/tools`;
  const archiveMatch = url.pathname.match(/^\/api\/agent\/skills\/(\d+)$/);
  if (!isCollection && !isTools && !archiveMatch) return null;

  const allowed = (isCollection && (request.method === "GET" || request.method === "POST"))
    || (isTools && request.method === "GET")
    || (archiveMatch && request.method === "DELETE");
  if (!allowed) return null;

  const userId = await resolveUser();
  if (userId instanceof Response) return userId;
  try {
    if (request.method === "GET" && isCollection) return success(await service.list(userId));
    if (request.method === "GET" && isTools) return success(service.listToolOptions());
    if (request.method === "DELETE" && archiveMatch) {
      const skillId = positiveId(archiveMatch[1]);
      if (!skillId) return apiError(400, "Skill ID 无效");
      return await service.archive(userId, skillId)
        ? success(null)
        : apiError(400, "Skill不存在或已归档");
    }

    const input = await readSkillInput(request);
    if (input instanceof Response) return input;
    return success(await service.create(userId, input));
  } catch (error) {
    if (error instanceof AgentSkillValidationError) return apiError(400, error.message);
    return apiError(503, "智能体服务暂不可用");
  }
}
