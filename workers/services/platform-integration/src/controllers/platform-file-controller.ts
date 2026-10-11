import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import { PlatformFileError, PlatformFileService } from "../services/platform-file-service.js";

const MAX_MULTIPART_REQUEST_BYTES = 51 * 1024 * 1024;
const PUBLIC_IMAGE_PREFIX = "/api/public/files/";

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function userId(value: string | null): number | null {
  if (!value || !/^[1-9]\d{0,17}$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function errorResponse(error: unknown): Response {
  return error instanceof PlatformFileError
    ? apiError(error.status, error.message)
    : apiError(503, "文件服务暂不可用");
}

async function multipart(request: Request): Promise<FormData | Response> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && (!/^\d+$/.test(contentLength) || Number(contentLength) > MAX_MULTIPART_REQUEST_BYTES)) {
    return apiError(413, "上传请求超过大小限制");
  }
  try {
    return await request.formData();
  } catch {
    return apiError(400, "上传请求格式不正确");
  }
}

function encodedFilename(filename: string): string {
  return encodeURIComponent(filename).replace(/[!'()*]/g, (character) =>
    `%${character.charCodeAt(0).toString(16).toUpperCase()}`);
}

function contentDisposition(filename: string, disposition = "attachment"): string {
  const fallback = filename.replace(/[^\x20-\x7e]|["\\]/g, "_");
  return `${disposition}; filename="${fallback}"; filename*=UTF-8''${encodedFilename(filename)}`;
}

async function downloadResponse(storedName: string, service: PlatformFileService, inline = false): Promise<Response> {
  const result = await service.download(storedName);
  if (!result) return new Response(null, { status: 404 });
  const disposition = inline && result.asset.contentType.toLowerCase().startsWith("image/")
    ? "inline" : "attachment";
  return new Response(result.object.body, {
    status: 200,
    headers: {
      "content-type": result.asset.contentType,
      "content-length": String(result.asset.byteSize),
      "content-disposition": contentDisposition(result.asset.originalName, disposition),
      "cache-control": "public, max-age=31536000, immutable",
      "x-content-type-options": "nosniff",
    },
  });
}

function clientUploadUrl(request: Request, upload: { url: string; contentType: string; storedName: string }) {
  const url = new URL(upload.url, request.url);
  if (upload.contentType.toLowerCase().startsWith("image/")) {
    url.pathname = `${PUBLIC_IMAGE_PREFIX}${encodeURIComponent(upload.storedName)}`;
    url.search = "";
  }
  return { ...upload, url: url.toString() };
}

/** Serves only registered image assets through a browser-readable public URL. */
export async function handlePublicPlatformImageRequest(
  request: Request,
  service: PlatformFileService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method !== "GET") return null;
  const publicImagePath = url.pathname.startsWith(PUBLIC_IMAGE_PREFIX);
  const legacyInlinePath = url.pathname.startsWith("/api/download/")
    && url.searchParams.get("inline") === "1";
  if (!publicImagePath && !legacyInlinePath) return null;
  const prefix = publicImagePath ? PUBLIC_IMAGE_PREFIX : "/api/download/";
  const storedName = url.pathname.slice(prefix.length);
  if (!storedName || storedName.includes("/")) return new Response(null, { status: 404 });

  try {
    const result = await service.downloadPublicImage(storedName);
    if (!result) return new Response(null, { status: 404 });
    return new Response(result.object.body, {
      status: 200,
      headers: {
        "access-control-allow-origin": "*",
        "cache-control": "public, max-age=31536000, immutable",
        "content-disposition": contentDisposition(result.asset.originalName, "inline"),
        "content-length": String(result.asset.byteSize),
        "content-type": result.asset.contentType,
        "cross-origin-resource-policy": "cross-origin",
        "x-content-type-options": "nosniff",
      },
    });
  } catch (error) {
    return errorResponse(error);
  }
}

const INTERNAL_FILE_PREFIXES = [
  "/internal/integration/files",
  "/internal/platform-integration/files",
];

function internalTokenMatches(request: Request, env: PlatformEnvironment): boolean {
  return Boolean(env.INTERNAL_SERVICE_TOKEN)
    && request.headers.get("x-dinqor-service-token") === env.INTERNAL_SERVICE_TOKEN;
}

function internalFilePath(pathname: string): string | null {
  return INTERNAL_FILE_PREFIXES.find((prefix) => pathname.startsWith(`${prefix}/`)) ?? null;
}

/** Maps multipart upload, public reads, and protected compatibility file reads to the file service. */
export async function handlePlatformFileRequest(
  request: Request,
  env: PlatformEnvironment,
  service: PlatformFileService,
): Promise<Response | null> {
  const url = new URL(request.url);
  const { pathname } = url;
  const internalPrefix = internalFilePath(pathname);
  if (internalPrefix && !internalTokenMatches(request, env)) return apiError(401, "Service authentication required");

  const publicUpload = request.method === "POST"
    && ["/api/upload", "/api/upload/batch", "/api/upload/oss"].includes(pathname);
  if (publicUpload) {
    const owner = userId(request.headers.get("x-user-id"));
    if (!owner) return apiError(401, "未登录或登录已过期");
    const data = await multipart(request);
    if (data instanceof Response) return data;
    if (pathname === "/api/upload/batch") {
      const files = data.getAll("files");
      if (files.some((file) => !(file instanceof File))) return apiError(400, "批量上传请求格式不正确");
      try {
        const uploaded = await service.uploadBatch(owner, files as File[]);
        return success(uploaded.map((file) => clientUploadUrl(request, file)));
      } catch (error) {
        return errorResponse(error);
      }
    }

    const file = data.get("file");
    if (!(file instanceof File)) return apiError(400, "上传文件不能为空");
    const category = pathname === "/api/upload" ? "upload"
      : data.get("type") === "avatar" ? "avatar"
        : data.get("type") === "video" ? "video" : "photo";
    try {
      const uploaded = await service.upload(owner, file, category);
      return success(clientUploadUrl(request, uploaded));
    } catch (error) {
      return errorResponse(error);
    }
  }

  const downloadPrefix = pathname.startsWith("/api/download/")
    ? "/api/download/"
    : INTERNAL_PREFIXES_FOR_DOWNLOAD(pathname, internalPrefix);
  if (request.method === "GET" && downloadPrefix) {
    let storedName: string;
    try {
      storedName = decodeURIComponent(pathname.slice(downloadPrefix.length));
    } catch {
      return apiError(400, "非法的文件路径");
    }
    if (!storedName || storedName.includes("/")) return apiError(400, "非法的文件路径");
    try {
      return await downloadResponse(storedName, service, url.searchParams.get("inline") === "1");
    } catch (error) {
      return errorResponse(error);
    }
  }

  const exportPath = pathname.match(/^\/api\/export\/(csv|excel)$/)
    ?? internalExportPath(pathname, internalPrefix);
  if (request.method === "GET" && exportPath) {
    try {
      const type = url.searchParams.get("type") ?? "blog";
      const report = service.export(type, exportPath[1] === "excel");
      const body = report.body.buffer.slice(
        report.body.byteOffset,
        report.body.byteOffset + report.body.byteLength,
      ) as ArrayBuffer;
      return new Response(body, {
        status: 200,
        headers: {
          "content-type": report.contentType,
          "content-disposition": contentDisposition(report.filename),
          "content-length": String(report.body.byteLength),
        },
      });
    } catch (error) {
      return errorResponse(error);
    }
  }
  return null;
}

function INTERNAL_PREFIXES_FOR_DOWNLOAD(pathname: string, prefix: string | null): string | null {
  if (!prefix) return null;
  return INTERNAL_FILE_PREFIXES
    .map((root) => `${root}/download/`)
    .find((candidate) => pathname.startsWith(candidate)) ?? null;
}

function internalExportPath(pathname: string, prefix: string | null): RegExpMatchArray | null {
  if (!prefix) return null;
  const roots = INTERNAL_FILE_PREFIXES.map((root) => `${root}/export/`);
  if (!roots.some((root) => pathname.startsWith(root))) return null;
  return pathname.match(/\/export\/(csv|excel)$/);
}
