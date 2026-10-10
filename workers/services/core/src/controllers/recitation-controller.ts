import { apiError, jsonResponse, type D1Binding } from "@dinqor/worker-runtime";
import { readJsonObject } from "./http-input.js";
import { resolveCurrentUser, type IdentityAuthEnvironment } from "./identity-auth-controller.js";
import type { IdentityAuthService } from "../services/identity-auth-service.js";
import { RecitationError, RecitationService } from "../services/recitation-service.js";
import { RecitationAudioService } from "../services/recitation-audio-service.js";

export interface RecitationEnvironment extends IdentityAuthEnvironment {
  DB: D1Binding;
  RECITATION_ENABLED?: string;
  RECITATION_MAX_AUDIO_BYTES?: string;
}

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function respondError(error: unknown): Response {
  if (error instanceof RecitationError) return apiError(error.status, error.message);
  return apiError(503, "背诵服务暂不可用");
}

function pathId(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

function parseBoolean(value: string | null, fallback: boolean): boolean | null {
  if (value === null) return fallback;
  if (value === "true") return true;
  if (value === "false") return false;
  return null;
}

async function multipartBytes(request: Request, maxBytes: number): Promise<Uint8Array | Response> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > maxBytes + 256 * 1024)) {
    return apiError(413, "录音文件过大");
  }
  if (!request.body) return apiError(400, "录音文件不能为空");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes + 256 * 1024) {
        await reader.cancel();
        return apiError(413, "录音文件过大");
      }
      chunks.push(value);
    }
  } catch {
    return apiError(400, "录音上传失败");
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return bytes;
}

async function readAudioRequest(request: Request, maxBytes: number) {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("multipart/form-data;")) return apiError(400, "录音请求格式不正确");
  const bytes = await multipartBytes(request, maxBytes);
  if (bytes instanceof Response) return bytes;
  try {
    const buffer = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(buffer).set(bytes);
    const form = await new Response(buffer, { headers: { "content-type": contentType } }).formData();
    const file = form.get("file");
    const clientRequestId = form.get("clientRequestId");
    if (!(file instanceof File) || typeof clientRequestId !== "string") return apiError(400, "录音文件或请求标识不能为空");
    return {
      name: file.name,
      type: file.type,
      size: file.size,
      bytes: new Uint8Array(await file.arrayBuffer()),
      clientRequestId,
    };
  } catch {
    return apiError(400, "录音请求格式不正确");
  }
}

export async function handleRecitationRequest(
  request: Request,
  env: RecitationEnvironment,
  auth: IdentityAuthService,
  service: RecitationService,
  audioService?: RecitationAudioService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (!url.pathname.startsWith("/api/recitations/")) return null;

  const collection = url.pathname === "/api/recitations/materials";
  const materialMatch = url.pathname.match(/^\/api\/recitations\/materials\/(\d+)$/);
  const archiveMatch = url.pathname.match(/^\/api\/recitations\/materials\/(\d+)\/archive$/);
  const attemptsMatch = url.pathname.match(/^\/api\/recitations\/materials\/(\d+)\/attempts$/);
  const quizzesMatch = url.pathname.match(/^\/api\/recitations\/materials\/(\d+)\/quizzes$/);
  const attemptMatch = url.pathname.match(/^\/api\/recitations\/attempts\/(\d+)$/);
  const isKnownRoute = collection || materialMatch || archiveMatch || attemptsMatch || quizzesMatch || attemptMatch;
  if (!isKnownRoute) return null;

  const user = await resolveCurrentUser(request, env, auth);
  if (user instanceof Response) return user;

  try {
    if (collection && request.method === "POST") {
      const body = await readJsonObject(request, 64 * 1024);
      if (body instanceof Response) return body;
      return success(await service.createMaterial(user.id, body));
    }
    if (collection && request.method === "GET") {
      const rawLimit = url.searchParams.get("limit");
      if (rawLimit !== null && !/^\d+$/.test(rawLimit)) return apiError(400, "limit 参数格式不正确");
      const parsedLimit = rawLimit === null ? 100 : Number(rawLimit);
      if (!Number.isSafeInteger(parsedLimit)) return apiError(400, "limit 参数格式不正确");
      const dueOnlyValue = url.searchParams.get("dueOnly");
      const dueOnly = parseBoolean(dueOnlyValue, false);
      if (dueOnly === null) return apiError(400, "dueOnly 参数格式不正确");
      return success(await service.listMaterials(user.id, {
        search: url.searchParams.get("search"),
        masteryLevel: url.searchParams.get("masteryLevel"),
        dueOnly,
        limit: parsedLimit,
        archive: url.searchParams.get("archive"),
      }));
    }

    if (materialMatch) {
      const materialId = pathId(materialMatch[1]);
      if (!materialId) return apiError(400, "背诵材料 ID 格式不正确");
      if (request.method === "GET") return success(await service.getMaterial(user.id, materialId));
      if (request.method === "PUT") {
        const body = await readJsonObject(request, 64 * 1024);
        if (body instanceof Response) return body;
        return success(await service.updateMaterial(user.id, materialId, body));
      }
      if (request.method === "DELETE") {
        await service.deleteMaterial(user.id, materialId);
        return success(null);
      }
    }

    if (archiveMatch && request.method === "POST") {
      const materialId = pathId(archiveMatch[1]);
      if (!materialId) return apiError(400, "背诵材料 ID 格式不正确");
      const archived = parseBoolean(url.searchParams.get("archived"), true);
      if (archived === null) return apiError(400, "archived 参数格式不正确");
      return success(await service.archiveMaterial(user.id, materialId, archived));
    }

    if (attemptsMatch && request.method === "GET") {
      const materialId = pathId(attemptsMatch[1]);
      if (!materialId) return apiError(400, "背诵材料 ID 格式不正确");
      return success(await service.listAttempts(user.id, materialId));
    }
    if (attemptsMatch && request.method === "POST") {
      const materialId = pathId(attemptsMatch[1]);
      if (!materialId) return apiError(400, "背诵材料 ID 格式不正确");
      const maxAudioBytes = Number(env.RECITATION_MAX_AUDIO_BYTES);
      const boundedLimit = Number.isSafeInteger(maxAudioBytes) && maxAudioBytes > 0
        ? Math.min(maxAudioBytes, 100 * 1024 * 1024) : 20 * 1024 * 1024;
      const body = await readAudioRequest(request, boundedLimit);
      if (body instanceof Response) return body;
      if (!audioService) return await service.createAttempt(user.id, env.RECITATION_ENABLED === "true");
      const created = await audioService.createAttempt(user.id, materialId, body, body.clientRequestId);
      return success(await service.getAttempt(user.id, created.attempt.id));
    }
    if (attemptMatch && request.method === "GET") {
      const attemptId = pathId(attemptMatch[1]);
      if (!attemptId) return apiError(400, "录音任务 ID 格式不正确");
      return success(await service.getAttempt(user.id, attemptId));
    }
    if (quizzesMatch && request.method === "GET") {
      const materialId = pathId(quizzesMatch[1]);
      if (!materialId) return apiError(400, "背诵材料 ID 格式不正确");
      return success(await service.listQuizzes(user.id, materialId));
    }
    return apiError(405, "请求方法不支持");
  } catch (error) {
    return respondError(error);
  }
}
