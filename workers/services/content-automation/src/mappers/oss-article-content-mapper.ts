import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssGetHeaders,
  signedOssPutHeaders,
} from "../oss.js";
import { ArticleContentTooLargeError } from "../interfaces/external-publication.js";
import type { ArticleContentStorage } from "../interfaces/blog-post-creation.js";
import type { ContentEnvironment } from "../interfaces/content-environment.js";

const MAX_ARTICLE_CONTENT_BYTES = 2 * 1024 * 1024;

async function readUtf8BodyWithinLimit(response: Response): Promise<string> {
  const contentLength = response.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_ARTICLE_CONTENT_BYTES) {
    throw new ArticleContentTooLargeError();
  }
  if (!response.body) return "";

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > MAX_ARTICLE_CONTENT_BYTES) {
        await reader.cancel();
        throw new ArticleContentTooLargeError();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const content = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    content.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(content);
}

export class OssArticleContentMapper implements ArticleContentStorage {
  constructor(private readonly env: ContentEnvironment) {}

  async read(objectKey: string): Promise<string> {
    const hasAccessKeyId = Boolean(this.env.ALIYUN_OSS_ACCESS_KEY_ID);
    const hasAccessKeySecret = Boolean(this.env.ALIYUN_OSS_ACCESS_KEY_SECRET);
    if (hasAccessKeyId || hasAccessKeySecret) return this.readSigned(objectKey);
    return this.readPublic(objectKey);
  }

  async write(objectKey: string, content: string): Promise<void> {
    const bytes = new TextEncoder().encode(content);
    if (bytes.byteLength > MAX_ARTICLE_CONTENT_BYTES) throw new ArticleContentTooLargeError();
    const { objectUrl, credentials } = this.privateObject(objectKey);
    const headers = await signedOssPutHeaders(objectUrl, credentials, "text/markdown; charset=utf-8");
    let response: Response;
    try {
      response = await fetch(objectUrl, {
        method: "PUT",
        headers,
        body: bytes,
        redirect: "manual",
        signal: AbortSignal.timeout(15_000),
      });
    } catch {
      throw new Error("Content object upload failed");
    }
    if (!response.ok) throw new Error("Content object upload failed");
  }

  async delete(objectKey: string): Promise<void> {
    const { objectUrl, credentials } = this.privateObject(objectKey);
    const headers = await signedOssDeleteHeaders(objectUrl, credentials);
    let response: Response;
    try {
      response = await fetch(objectUrl, {
        method: "DELETE",
        headers,
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
    } catch {
      throw new Error("Content object cleanup failed");
    }
    if (!response.ok && response.status !== 404) throw new Error("Content object cleanup failed");
  }

  private async readPublic(objectKey: string): Promise<string> {
    const objectUrl = this.publicObjectUrl(objectKey);
    const response = await fetch(objectUrl, {
      method: "GET",
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error("Content object fetch failed");
    return readUtf8BodyWithinLimit(response);
  }

  private async readSigned(objectKey: string): Promise<string> {
    const { objectUrl, credentials } = this.privateObject(objectKey);
    const headers = await signedOssGetHeaders(objectUrl, credentials);
    const response = await fetch(objectUrl, {
      method: "GET",
      headers,
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error("Content object fetch failed");
    return readUtf8BodyWithinLimit(response);
  }

  private privateObject(objectKey: string) {
    const { ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET, ALIYUN_OSS_REGION, ALIYUN_OSS_ENDPOINT } = this.env;
    if (!ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET || !ALIYUN_OSS_ENDPOINT) {
      throw new Error("Aliyun OSS credentials and endpoint configuration are incomplete");
    }
    const region = ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
    if (!region) throw new Error("Aliyun OSS region is required for a custom endpoint");
    const objectPrefix = this.env.CONTENT_OSS_OBJECT_PREFIX ?? "article-content";
    const objectUrl = createOssObjectUrl(
      ALIYUN_OSS_ENDPOINT,
      `${objectPrefix}/${objectKey}`,
      this.env.ALIYUN_OSS_BUCKET,
    );
    return {
      objectUrl,
      credentials: {
        accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID,
        accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET,
        region,
      },
    };
  }

  private publicObjectUrl(objectKey: string): URL {
    if (!this.env.CONTENT_OSS_PUBLIC_BASE_URL) throw new Error("Content OSS public base URL is not configured");
    const base = new URL(this.env.CONTENT_OSS_PUBLIC_BASE_URL);
    if (base.protocol !== "https:" || base.username || base.password || base.search || base.hash) {
      throw new Error("Content OSS public base URL must be a clean HTTPS URL");
    }
    if (objectKey.startsWith("/") || objectKey.includes("\\")) throw new Error("Invalid content object key");
    const segments = objectKey.split("/");
    if (segments.some((segment) => !segment || segment === "." || segment === "..")) {
      throw new Error("Invalid content object key");
    }
    const basePath = base.pathname.replace(/\/+$/, "");
    base.pathname = `${basePath}/${segments.map(encodeURIComponent).join("/")}`;
    return base;
  }
}
