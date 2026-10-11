import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssHeadHeaders,
  signedOssPutHeaders,
} from "@dinqor/worker-runtime/oss";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";

const ROUTE_PATH = "/__ops/oss-smoke-test";
const OBJECT_KEY = "diagnostics/agent-oss-smoke-test.txt";
const OBJECT_CONTENT = "DinQorAI Agent OSS smoke test\n";

function errorCode(response: Response, body = ""): string | null {
  const headerCode = response.headers.get("x-oss-ec");
  if (headerCode && /^[a-z0-9._-]{1,100}$/i.test(headerCode)) return headerCode;
  return body.match(/<Code>\s*([A-Za-z0-9_-]{1,80})\s*<\/Code>/i)?.[1] ?? null;
}

async function hasRequestBody(request: Request): Promise<boolean> {
  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) return !/^\d+$/.test(contentLength) || Number(contentLength) > 0;
  if (!request.body) return false;
  const reader = request.body.getReader();
  try {
    const firstChunk = await reader.read();
    return !firstChunk.done;
  } catch {
    return true;
  } finally {
    try { await reader.cancel(); } catch { /* request is already ending */ }
    reader.releaseLock();
  }
}

/** Narrow unauthenticated diagnostic route; disabled except during operator testing. */
export async function handleAgentOssSmokeTestRequest(
  request: Request,
  env: AgentEnvironment,
): Promise<Response | null> {
  if (new URL(request.url).pathname !== ROUTE_PATH) return null;
  if (env.AGENT_OSS_SMOKE_TEST_ENABLED !== "true") {
    console.warn("agent.oss-smoke-test.disabled", {
      configured: env.AGENT_OSS_SMOKE_TEST_ENABLED === "false" ? "false" : "unset",
    });
    return apiError(404, "Route not found");
  }
  if (request.method !== "POST") return apiError(405, "Only POST is allowed");
  if (await hasRequestBody(request)) return apiError(400, "This diagnostic route does not accept a request body");

  const { ALIYUN_OSS_ENDPOINT, ALIYUN_OSS_BUCKET, ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET } = env;
  if (!ALIYUN_OSS_ENDPOINT || !ALIYUN_OSS_BUCKET || !ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET) {
    return apiError(503, "OSS configuration is incomplete");
  }
  const region = env.ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
  if (!region) return apiError(503, "OSS region is not configured");

  try {
    const prefix = env.AGENT_OSS_OBJECT_PREFIX ?? "agent-attachments";
    const objectUrl = createOssObjectUrl(ALIYUN_OSS_ENDPOINT, `${prefix}/${OBJECT_KEY}`, ALIYUN_OSS_BUCKET);
    const credentials = { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region };
    const body = new TextEncoder().encode(OBJECT_CONTENT);
    const payload = new ArrayBuffer(body.byteLength);
    new Uint8Array(payload).set(body);
    const put = await fetch(objectUrl, {
      method: "PUT",
      headers: await signedOssPutHeaders(objectUrl, credentials, "text/plain; charset=utf-8"),
      body: payload,
      redirect: "manual",
      signal: AbortSignal.timeout(20_000),
    });
    if (!put.ok) {
      const body = await put.text().catch(() => "");
      const details = { operation: "put", ossStatus: put.status, ossCode: errorCode(put, body), requestId: put.headers.get("x-oss-request-id") };
      console.warn("agent.oss-smoke-test.put-failed", details);
      return apiError(502, "OSS upload failed", details);
    }

    const head = await fetch(objectUrl, {
      method: "HEAD",
      headers: await signedOssHeadHeaders(objectUrl, credentials),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!head.ok) {
      const details = { operation: "head", ossStatus: head.status, ossCode: errorCode(head), requestId: head.headers.get("x-oss-request-id") };
      console.warn("agent.oss-smoke-test.head-failed", details);
      return apiError(502, "OSS read check failed", details);
    }

    const success = jsonResponse({
      code: 200,
      message: "success",
      data: {
        uploaded: true,
        headVerified: true,
        objectKey: `${prefix}/${OBJECT_KEY}`,
        putStatus: put.status,
        putRequestId: put.headers.get("x-oss-request-id"),
        headStatus: head.status,
        headRequestId: head.headers.get("x-oss-request-id"),
      },
    });
    success.headers.set("cache-control", "no-store");
    return success;
  } catch (error) {
    console.warn("agent.oss-smoke-test.request-failed", error instanceof Error ? error.name : "UnknownError");
    return apiError(502, "Unable to reach OSS");
  }
}
