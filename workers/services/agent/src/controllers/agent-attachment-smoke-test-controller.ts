import { apiError } from "@dinqor/worker-runtime";
import { D1AgentAttachmentMapper } from "../mappers/d1-agent-attachment-mapper.js";
import { OssAgentAttachmentStorageMapper } from "../mappers/oss-agent-attachment-storage-mapper.js";
import type { AgentAttachmentRepository, AgentAttachmentStorage } from "../interfaces/agent-attachment.js";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";
import { AgentAttachmentService } from "../services/agent-attachment-service.js";
import { ContentAgentMediaMapper } from "../mappers/content-agent-media-mapper.js";
import { handleAgentAttachmentRequest } from "./agent-attachment-controller.js";
import { createOssObjectUrl, inferOssRegion, signedOssDeleteHeaders, signedOssGetHeaders } from "@dinqor/worker-runtime/oss";

const ROUTE_PATH = "/__ops/attachment-smoke-test";
const TEST_USER_ID = 2_147_483_647;
const TEST_TEXT = "DinQorAI Agent attachment smoke test\n";
const TEST_PNG_HEX = "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000b4944415408d763f8cfc000000301010018dd8db10000000049454e44ae426082";

function safeOssCode(value: string | null): string | null {
  return value && /^[a-z0-9._-]{1,100}$/i.test(value) ? value : null;
}

function bodyOssCode(body: string): string | null {
  return safeOssCode(body.match(/<Code>\s*([A-Za-z0-9_-]{1,80})\s*<\/Code>/i)?.[1] ?? null);
}

function testPng(): Uint8Array {
  const bytes = new Uint8Array(TEST_PNG_HEX.length / 2);
  for (let index = 0; index < bytes.length; index++) {
    bytes[index] = Number.parseInt(TEST_PNG_HEX.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

function ossCredentials(env: AgentEnvironment) {
  const { ALIYUN_OSS_ENDPOINT, ALIYUN_OSS_BUCKET, ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET } = env;
  if (!ALIYUN_OSS_ENDPOINT || !ALIYUN_OSS_BUCKET || !ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET) {
    throw new Error("OSS configuration is incomplete");
  }
  const region = env.ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
  if (!region) throw new Error("OSS region is not configured");
  return {
    endpoint: ALIYUN_OSS_ENDPOINT,
    bucket: ALIYUN_OSS_BUCKET,
    credentials: { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region },
  };
}

async function probeSignedGet(env: AgentEnvironment, objectKey: string) {
  try {
    const { endpoint, bucket, credentials } = ossCredentials(env);
    const prefix = env.AGENT_OSS_OBJECT_PREFIX ?? "agent-attachments";
    const objectUrl = createOssObjectUrl(endpoint, `${prefix}/${objectKey}`, bucket);
    const response = await fetch(objectUrl, {
      method: "GET",
      headers: await signedOssGetHeaders(objectUrl, credentials),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    const body = response.ok ? "" : await response.text().catch(() => "");
    if (response.ok) await response.arrayBuffer();
    return {
      status: response.status,
      errorCode: safeOssCode(response.headers.get("x-oss-ec")) ?? bodyOssCode(body),
      requestId: safeOssCode(response.headers.get("x-oss-request-id")),
      serverStringToSignPresent: /<StringToSign>/i.test(body),
    };
  } catch (error) {
    return { status: null, errorCode: null, requestId: null, error: error instanceof Error ? error.name : "UnknownError" };
  }
}

async function deleteCurrentTestObject(env: AgentEnvironment, objectKey: string): Promise<void> {
  const { endpoint, bucket, credentials } = ossCredentials(env);
  const prefix = env.AGENT_OSS_OBJECT_PREFIX ?? "agent-attachments";
  const objectUrl = createOssObjectUrl(endpoint, `${prefix}/${objectKey}`, bucket);
  const response = await fetch(objectUrl, {
    method: "DELETE",
    headers: await signedOssDeleteHeaders(objectUrl, credentials),
    redirect: "manual",
    signal: AbortSignal.timeout(10_000),
  });
  if (response.ok || response.status === 404) return;
  const body = await response.text().catch(() => "");
  console.error("agent.attachment-smoke-test.direct-delete-failed", {
    status: response.status,
    errorCode: safeOssCode(response.headers.get("x-oss-ec")) ?? bodyOssCode(body),
    requestId: safeOssCode(response.headers.get("x-oss-request-id")),
  });
  throw new Error("Agent attachment diagnostic object cleanup failed");
}

async function hasRequestBody(request: Request): Promise<boolean> {
  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) return !/^\d+$/.test(contentLength) || Number(contentLength) > 0;
  if (!request.body) return false;
  const reader = request.body.getReader();
  try {
    return !(await reader.read()).done;
  } catch {
    return true;
  } finally {
    try { await reader.cancel(); } catch { /* request is already ending */ }
    reader.releaseLock();
  }
}

/** One-shot no-auth probe through the real attachment controller, D1, and OSS storage adapter. */
export async function handleAgentAttachmentSmokeTestRequest(
  request: Request,
  env: AgentEnvironment,
): Promise<Response | null> {
  if (new URL(request.url).pathname !== ROUTE_PATH) return null;
  if (env.AGENT_OSS_SMOKE_TEST_ENABLED !== "true") return apiError(404, "Route not found");
  if (request.method !== "POST") return apiError(405, "Only POST is allowed");
  if (await hasRequestBody(request)) return apiError(400, "This diagnostic route does not accept a request body");

  const sample = new URL(request.url).searchParams.get("sample");
  const sampleType = sample === "image" || sample === "image-no-media" ? "image" : "text";
  const includeMediaAsset = sample !== "image-no-media";
  const fileBytes = sampleType === "image" ? testPng() : new TextEncoder().encode(TEST_TEXT);
  const fileName = sampleType === "image" ? "attachment-smoke.png" : "attachment-smoke.txt";
  const contentType = sampleType === "image" ? "image/png" : "text/plain";
  const storage = new OssAgentAttachmentStorageMapper(env);
  const repository = new D1AgentAttachmentMapper(env.DB);
  const contentMedia = new ContentAgentMediaMapper(env);
  let objectKey: string | undefined;
  let mediaAssetId: number | null = null;
  let ossWriteCompleted = false;
  let metadataCreated = false;
  let signedReadUrlCompleted = false;
  let signedGetProbe: Awaited<ReturnType<typeof probeSignedGet>> | null = null;
  let failureStage: string | null = null;
  let attachmentStatus = 500;
  let attachmentMessage = "Attachment diagnostic failed";
  let metadataCleanup: "not-needed" | "deleted" | "failed" = "not-needed";
  let objectCleanup: "not-needed" | "deleted" | "failed" = "not-needed";
  let mediaCleanup: "not-needed" | "deleted" | "failed" = "not-needed";

  const trackedStorage: AgentAttachmentStorage = {
    async write(key, bytes, contentType) {
      objectKey = key;
      try {
        await storage.write(key, bytes, contentType);
        ossWriteCompleted = true;
      } catch (error) {
        failureStage = "oss-write";
        throw error;
      }
    },
    delete(key) { return storage.delete(key); },
    async signedReadUrl(key) {
      try {
        const url = await storage.signedReadUrl(key);
        signedReadUrlCompleted = true;
        return url;
      } catch (error) {
        failureStage = "signed-read-url";
        if (objectKey && ossWriteCompleted) signedGetProbe = await probeSignedGet(env, objectKey);
        throw error;
      }
    },
  };
  const trackedMediaAssets = {
    async uploadImage(userId: number, originalName: string, type: string, bytes: Uint8Array) {
      try {
        const asset = await contentMedia.uploadImage(userId, originalName, type, bytes);
        mediaAssetId = asset.id;
        return asset;
      } catch (error) {
        failureStage = "media-asset";
        throw error;
      }
    },
    async deleteAsset(userId: number, id: number) {
      try {
        await contentMedia.deleteAsset(userId, id);
        mediaCleanup = "deleted";
      } catch (error) {
        mediaCleanup = "failed";
        throw error;
      }
    },
  };
  const trackedRepository: AgentAttachmentRepository = {
    async create(input) {
      try {
        const row = await repository.create(input);
        metadataCreated = row !== null;
        if (!metadataCreated) failureStage = "metadata";
        return row;
      } catch (error) {
        failureStage = "metadata";
        throw error;
      }
    },
    findOwned(userId, id) { return repository.findOwned(userId, id); },
    findOwnedMany(userId, ids) { return repository.findOwnedMany(userId, ids); },
  };
  const service = new AgentAttachmentService(
    trackedRepository,
    trackedStorage,
    includeMediaAsset ? trackedMediaAssets : undefined,
  );

  try {
    const form = new FormData();
    const fileBuffer = new ArrayBuffer(fileBytes.byteLength);
    new Uint8Array(fileBuffer).set(fileBytes);
    form.set("file", new File([fileBuffer], fileName, { type: contentType }));
    const attachmentRequest = new Request(new URL("/api/agent/conversations/attachments", request.url), {
      method: "POST",
      body: form,
    });
    const response = await handleAgentAttachmentRequest(
      attachmentRequest,
      service,
      async () => TEST_USER_ID,
    );
    attachmentStatus = response?.status ?? 404;
    const body = await response?.json().catch(() => null) as { message?: unknown } | null;
    attachmentMessage = typeof body?.message === "string" ? body.message : "Attachment request completed";
  } catch (error) {
    failureStage ??= "attachment-controller";
    attachmentMessage = error instanceof Error ? error.name : "UnknownError";
  } finally {
    if (objectKey) {
      try {
        await env.DB.prepare("DELETE FROM agent_attachment WHERE user_id = ? AND object_key = ?")
          .bind(TEST_USER_ID, objectKey).run();
        metadataCleanup = "deleted";
      } catch (error) {
        metadataCleanup = "failed";
        console.error("agent.attachment-smoke-test.metadata-cleanup-failed", {
          error: error instanceof Error ? error.name : "UnknownError",
        });
      }
      if (ossWriteCompleted) {
        try {
          await deleteCurrentTestObject(env, objectKey);
          objectCleanup = "deleted";
        } catch (error) {
          objectCleanup = "failed";
          console.error("agent.attachment-smoke-test.object-cleanup-failed", {
            error: error instanceof Error ? error.name : "UnknownError",
          });
        }
      }
    }
    if (mediaAssetId !== null) {
      try {
        await contentMedia.deleteAsset(TEST_USER_ID, mediaAssetId);
        mediaCleanup = "deleted";
      } catch (error) {
        mediaCleanup = "failed";
        console.error("agent.attachment-smoke-test.media-cleanup-failed", {
          error: error instanceof Error ? error.name : "UnknownError",
        });
      }
    }
  }

  const result = new Response(JSON.stringify({
    code: attachmentStatus,
    message: attachmentMessage,
    data: {
      ossWriteCompleted,
      metadataCreated,
      signedReadUrlCompleted,
      signedGetProbe,
      failureStage,
      sampleType,
      mediaAssetEnabled: includeMediaAsset,
      testObjectKey: objectKey ?? null,
      cleanup: { metadata: metadataCleanup, object: objectCleanup, mediaAsset: mediaCleanup },
    },
  }), {
    status: attachmentStatus,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
  return result;
}
