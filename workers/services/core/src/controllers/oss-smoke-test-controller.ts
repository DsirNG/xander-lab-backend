import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssHeadHeaders,
  signedOssPutHeaders,
} from "@dinqor/worker-runtime/oss";

export interface OssSmokeTestEnvironment {
  CORE_OSS_SMOKE_TEST_ENABLED?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
}

const ROUTE_PATH = "/__ops/oss-smoke-test";
const OBJECT_KEY = "agent-attachments/diagnostics/core-oss-smoke-test.txt";
const OBJECT_CONTENT = "DinQorAI Core OSS smoke test\n";
const CONTENT_TYPE = "text/plain; charset=utf-8";

function ossErrorCode(body: string, response?: Response): string | null {
  const headerCode = response?.headers.get("x-oss-ec");
  if (headerCode && /^[A-Za-z0-9._-]{1,100}$/.test(headerCode)) return headerCode;
  const code = body.match(/<Code>\s*([A-Za-z0-9_-]{1,80})\s*<\/Code>/i)?.[1];
  return code ?? null;
}

async function hasRequestBody(request: Request): Promise<boolean> {
  const contentLength = request.headers.get("content-length");
  if (contentLength !== null) {
    if (!/^\d+$/.test(contentLength)) return true;
    return Number(contentLength) > 0;
  }
  if (!request.body) return false;

  const reader = request.body.getReader();
  let hasBody = true;
  try {
    const firstChunk = await reader.read();
    hasBody = !firstChunk.done;
    return hasBody;
  } catch {
    return true;
  } finally {
    if (hasBody) {
      try { await reader.cancel(); } catch { /* the request is already ending */ }
    }
    reader.releaseLock();
  }
}

/**
 * A deliberately narrow, unauthenticated diagnostic route. It never accepts
 * caller-supplied bytes and always overwrites one fixed, tiny object. Keep the
 * route disabled unless an operator is actively testing OSS credentials.
 */
export async function handleOssSmokeTestRequest(
  request: Request,
  env: OssSmokeTestEnvironment,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== ROUTE_PATH) return null;
  if (env.CORE_OSS_SMOKE_TEST_ENABLED !== "true") return apiError(404, "Route not found");
  if (request.method !== "POST") return apiError(405, "Only POST is allowed");
  if (await hasRequestBody(request)) {
    return apiError(400, "This diagnostic route does not accept a request body");
  }

  const {
    ALIYUN_OSS_ENDPOINT,
    ALIYUN_OSS_BUCKET,
    ALIYUN_OSS_REGION,
    ALIYUN_OSS_ACCESS_KEY_ID,
    ALIYUN_OSS_ACCESS_KEY_SECRET,
  } = env;
  if (!ALIYUN_OSS_ENDPOINT || !ALIYUN_OSS_BUCKET
    || !ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET) {
    return apiError(503, "OSS configuration is incomplete", {
      required: ["ALIYUN_OSS_ENDPOINT", "ALIYUN_OSS_BUCKET", "ALIYUN_OSS_ACCESS_KEY_ID", "ALIYUN_OSS_ACCESS_KEY_SECRET"],
    });
  }

  const region = ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
  if (!region) return apiError(503, "OSS region is not configured");

  try {
    const objectUrl = createOssObjectUrl(ALIYUN_OSS_ENDPOINT, OBJECT_KEY, ALIYUN_OSS_BUCKET);
    const body = new TextEncoder().encode(OBJECT_CONTENT);
    const payload = new ArrayBuffer(body.byteLength);
    new Uint8Array(payload).set(body);
    const response = await fetch(objectUrl, {
      method: "PUT",
      headers: await signedOssPutHeaders(objectUrl, {
        accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID,
        accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET,
        region,
      }, CONTENT_TYPE),
      body: payload,
      redirect: "manual",
      signal: AbortSignal.timeout(20_000),
    });

    const requestId = response.headers.get("x-oss-request-id");
    if (!response.ok) {
      let code: string | null = null;
      try { code = ossErrorCode((await response.text()).slice(0, 4096), response); } catch { /* best-effort diagnostics */ }
      console.warn("core.oss-smoke-test.put-failed", {
        status: response.status,
        code,
        requestId,
      });
      return apiError(502, "OSS upload failed", {
        ossStatus: response.status,
        ossCode: code,
        requestId,
      });
    }

    const headResponse = await fetch(objectUrl, {
      method: "HEAD",
      headers: await signedOssHeadHeaders(objectUrl, {
        accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID,
        accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET,
        region,
      }),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    const headRequestId = headResponse.headers.get("x-oss-request-id");
    if (!headResponse.ok) {
      const code = ossErrorCode("", headResponse);
      console.warn("core.oss-smoke-test.head-failed", {
        status: headResponse.status,
        code,
        requestId: headRequestId,
      });
      return apiError(502, "OSS read check failed", {
        operation: "head",
        ossStatus: headResponse.status,
        ossCode: code,
        requestId: headRequestId,
      });
    }

    const success = jsonResponse({
      code: 200,
      message: "success",
      data: {
        uploaded: true,
        headVerified: true,
        objectKey: OBJECT_KEY,
        putStatus: response.status,
        putRequestId: requestId,
        headStatus: headResponse.status,
        headRequestId,
      },
    });
    success.headers.set("cache-control", "no-store");
    return success;
  } catch {
    console.warn("core.oss-smoke-test.request-failed");
    return apiError(502, "Unable to reach OSS");
  }
}
