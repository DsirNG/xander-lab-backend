import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { AgentRecitationError, AgentRecitationService } from "../services/agent-recitation-service.js";

const BASE_PATH = "/internal/agent/recitation";
const MAX_BODY_BYTES = 128 * 1024;
type JsonObject = Record<string, unknown>;

async function readObject(request: Request): Promise<JsonObject | Response> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
  try {
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value)) return apiError(400, "请求内容格式不正确");
    return value as JsonObject;
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

function nullableId(value: unknown): number | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) return undefined;
  return value;
}

function responseError(error: unknown): Response {
  if (error instanceof AgentRecitationError) return apiError(error.status, error.message);
  if (error && typeof error === "object" && "status" in error && typeof error.status === "number") {
    return apiError(error.status, "背诵服务拒绝了该操作");
  }
  return apiError(503, "背诵服务暂不可用");
}

function narrowMaterial(value: Record<string, unknown>) {
  return {
    id: value.id,
    title: value.title,
    content: value.content,
    knowledgeType: value.knowledgeType,
    testMode: value.testMode,
    masteryScore: value.masteryScore,
    masteryLevel: value.masteryLevel,
    reviewCount: value.reviewCount,
    nextReviewAt: value.nextReviewAt,
  };
}

export async function handleAgentRecitationRequest(
  request: Request,
  service: AgentRecitationService,
): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (!path.startsWith(`${BASE_PATH}/`)) return null;
  if (request.method !== "POST") return apiError(404, "Not Found");
  const body = await readObject(request);
  if (body instanceof Response) return body;
  const userId = nullableId(body.userId);
  if (userId === undefined) return apiError(400, "用户 ID 格式不正确");

  try {
    if (path === `${BASE_PATH}/material/create`) {
      if (!body.draft || typeof body.draft !== "object" || Array.isArray(body.draft)) {
        return apiError(400, "材料格式不正确");
      }
      const created = await service.createMaterial(userId ?? 0, body.draft);
      return jsonResponse(narrowMaterial(created as unknown as Record<string, unknown>));
    }

    if (path === `${BASE_PATH}/material/search` || path === `${BASE_PATH}/material/count`) {
      const filter = body.filter && typeof body.filter === "object" && !Array.isArray(body.filter)
        ? body.filter as Record<string, unknown> : {};
      if (path.endsWith("/count")) {
        return jsonResponse({ count: await service.countMaterials(userId ?? 0, filter) });
      }
      const materials = await service.searchMaterials(userId ?? 0, filter);
      return jsonResponse({ materials: materials.map((item) => narrowMaterial(item as unknown as Record<string, unknown>)) });
    }

    if (path === `${BASE_PATH}/material/get`) {
      const materialId = nullableId(body.materialId);
      if (materialId === undefined || materialId === null) return apiError(400, "材料 ID 格式不正确");
      const material = await service.getMaterial(userId ?? 0, materialId);
      return jsonResponse(narrowMaterial(material as unknown as Record<string, unknown>));
    }

    if (path === `${BASE_PATH}/quiz/open`) {
      const materialId = nullableId(body.materialId);
      const conversationId = nullableId(body.conversationId);
      if (materialId === undefined || materialId === null || conversationId === undefined) {
        return apiError(400, "测验请求格式不正确");
      }
      return jsonResponse(await service.openQuiz(userId ?? 0, materialId, conversationId));
    }

    if (path === `${BASE_PATH}/quiz/record`) {
      const quizSessionId = nullableId(body.quizSessionId);
      const conversationId = nullableId(body.conversationId);
      if (quizSessionId === undefined || quizSessionId === null || conversationId === undefined) {
        return apiError(400, "测验请求格式不正确");
      }
      return jsonResponse(await service.recordQuiz(
        userId ?? 0,
        quizSessionId,
        conversationId,
        body.verdict,
        body.items,
      ));
    }
    return apiError(404, "Not Found");
  } catch (error) {
    return responseError(error);
  }
}
