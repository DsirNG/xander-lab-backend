import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { PreviewService, PreviewServiceError } from "../services/preview-service.js";

const MAX_JSON_BODY_BYTES = 2 * 1024 * 1024;

function success<T>(data: T): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

async function readJsonBody(request: Request): Promise<unknown> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && (!/^\d+$/.test(contentLength) || Number(contentLength) > MAX_JSON_BODY_BYTES)) {
    throw new PreviewServiceError(413, "请求内容过大");
  }
  if (!request.body) throw new PreviewServiceError(400, "请求 JSON 无效");

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > MAX_JSON_BODY_BYTES) {
        await reader.cancel();
        throw new PreviewServiceError(413, "请求内容过大");
      }
      chunks.push(value);
    }
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
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
  } catch {
    throw new PreviewServiceError(400, "请求 JSON 无效");
  }
}

function isPublicRead(method: string, pathname: string): boolean {
  return method === "GET" && (pathname === "/raw" || pathname === "/raw/"
    || /^\/blog-html\/[^/]+\/raw\/?$/.test(pathname));
}

function sessionIdFromRequest(request: Request, pathname: string): string | null {
  const routeMatch = /^\/blog-html\/([^/]+)\/raw\/?$/.exec(pathname);
  if (routeMatch) return routeMatch[1];
  if (pathname === "/raw" || pathname === "/raw/") {
    return new URL(request.url).hostname.split(".")[0] ?? null;
  }
  return null;
}

function requireServiceToken(request: Request, expected?: string): boolean {
  return Boolean(expected) && request.headers.get("x-dinqor-service-token") === expected;
}

/** Maps preview HTTP contracts to the preview use cases; all D1 and OSS work stays behind ports. */
export async function handlePreviewRequest(
  request: Request,
  service: PreviewService,
  internalServiceToken?: string,
): Promise<Response> {
  const url = new URL(request.url);
  if (!isPublicRead(request.method, url.pathname) && !requireServiceToken(request, internalServiceToken)) {
    return apiError(401, "Service authentication required");
  }

  try {
    if (request.method === "POST" && url.pathname === "/api/blog-html/previews") {
      let payload: unknown;
      try {
        payload = await readJsonBody(request);
      } catch (error) {
        if (error instanceof PreviewServiceError) throw error;
        throw new PreviewServiceError(400, "请求 JSON 无效");
      }
      const clientIp = request.headers.get("x-dinqor-client-ip")
        ?? request.headers.get("cf-connecting-ip")
        ?? "unknown";
      return success(await service.create(payload, clientIp));
    }

    if (request.method === "GET") {
      const sessionId = sessionIdFromRequest(request, url.pathname);
      if (sessionId !== null) {
        const preview = await service.render(sessionId);
        return new Response(preview.html, {
          status: 200,
          headers: {
            "content-type": "text/html; charset=UTF-8",
            "content-security-policy": `default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; base-uri 'none'; form-action 'none'; frame-ancestors ${preview.frameAncestors}`,
            "cache-control": "no-store",
            "x-content-type-options": "nosniff",
          },
        });
      }
    }
    return apiError(404, "Route not found");
  } catch (error) {
    if (error instanceof PreviewServiceError) return apiError(error.status, error.message);
    return apiError(503, "预览服务暂不可用");
  }
}
