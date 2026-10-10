import {
  healthResponse,
  readyResponse,
  type D1Binding,
  type ServiceEnvironment,
} from "@dinqor/worker-runtime";
import { handlePreviewRequest } from "./controllers/preview-controller.js";
import { D1PreviewMapper } from "./mappers/d1-preview-mapper.js";
import { OssPreviewStorageMapper, type PreviewOssEnvironment } from "./mappers/oss-preview-storage-mapper.js";
import { PreviewService } from "./services/preview-service.js";

interface PreviewEnvironment extends ServiceEnvironment, PreviewOssEnvironment {
  DB: D1Binding;
  INTERNAL_SERVICE_TOKEN?: string;
  BLOG_HTML_PREVIEW_STORAGE_MODE?: string;
  BLOG_HTML_PREVIEW_PAGE_URL_PATTERN?: string;
  BLOG_HTML_PREVIEW_FRAME_ANCESTORS?: string;
  BLOG_HTML_PREVIEW_TTL_SECONDS?: string;
  BLOG_HTML_PREVIEW_MAX_CODE_BYTES?: string;
  BLOG_HTML_PREVIEW_RATE_LIMIT_MAX_REQUESTS?: string;
  BLOG_HTML_PREVIEW_RATE_LIMIT_WINDOW_SECONDS?: string;
}

function createService(env: PreviewEnvironment): PreviewService {
  return new PreviewService(
    new D1PreviewMapper(env.DB),
    new OssPreviewStorageMapper(env),
    {
      internalServiceToken: env.INTERNAL_SERVICE_TOKEN,
      storageMode: env.BLOG_HTML_PREVIEW_STORAGE_MODE,
      pageUrlPattern: env.BLOG_HTML_PREVIEW_PAGE_URL_PATTERN,
      frameAncestors: env.BLOG_HTML_PREVIEW_FRAME_ANCESTORS,
      ttlSeconds: env.BLOG_HTML_PREVIEW_TTL_SECONDS,
      maxCodeBytes: env.BLOG_HTML_PREVIEW_MAX_CODE_BYTES,
      rateLimitMaxRequests: env.BLOG_HTML_PREVIEW_RATE_LIMIT_MAX_REQUESTS,
      rateLimitWindowSeconds: env.BLOG_HTML_PREVIEW_RATE_LIMIT_WINDOW_SECONDS,
    },
  );
}

export default {
  async fetch(request: Request, env: PreviewEnvironment): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/healthz") {
      return healthResponse("bloghtml-preview");
    }
    if (request.method === "GET" && url.pathname === "/readyz") {
      return readyResponse("bloghtml-preview", env.DB);
    }
    return handlePreviewRequest(request, createService(env), env.INTERNAL_SERVICE_TOKEN);
  },

  async scheduled(_controller: unknown, env: PreviewEnvironment): Promise<void> {
    await createService(env).cleanup();
  },
};
