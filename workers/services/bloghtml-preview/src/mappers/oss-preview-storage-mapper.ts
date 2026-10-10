import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssGetHeaders,
  signedOssPutHeaders,
  type OssV4Credentials,
} from "@dinqor/worker-runtime/oss";
import type { PreviewStorage } from "../interfaces/preview-storage.js";

export interface PreviewOssEnvironment {
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_BUCKET?: string;
  BLOG_HTML_PREVIEW_OSS_PREFIX?: string;
}

/** Signs private preview object operations with the service-owned OSS credentials. */
export class OssPreviewStorageMapper implements PreviewStorage {
  constructor(private readonly env: PreviewOssEnvironment) {}

  async put(objectKey: string, code: string): Promise<void> {
    const url = this.objectUrl(objectKey);
    const headers = await signedOssPutHeaders(url, this.credentials(), "text/html; charset=utf-8");
    const response = await fetch(url, {
      method: "PUT",
      headers,
      body: code,
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error("Preview object upload failed");
  }

  async read(objectKey: string, maxBytes: number): Promise<string | null> {
    const url = this.objectUrl(objectKey);
    const headers = await signedOssGetHeaders(url, this.credentials());
    const response = await fetch(url, {
      method: "GET",
      headers,
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error("Preview object fetch failed");
    return readUtf8BodyWithinLimit(response, maxBytes);
  }

  async delete(objectKey: string): Promise<void> {
    const url = this.objectUrl(objectKey);
    const headers = await signedOssDeleteHeaders(url, this.credentials());
    const response = await fetch(url, {
      method: "DELETE",
      headers,
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok && response.status !== 404) throw new Error("Preview object deletion failed");
  }

  private objectUrl(objectKey: string): URL {
    if (!this.env.ALIYUN_OSS_ENDPOINT) throw new Error("BlogHTML Preview OSS endpoint is not configured");
    const prefix = this.env.BLOG_HTML_PREVIEW_OSS_PREFIX ?? "blog-html-preview";
    return createOssObjectUrl(this.env.ALIYUN_OSS_ENDPOINT, `${prefix}/${objectKey}`, this.env.ALIYUN_OSS_BUCKET);
  }

  private credentials(): OssV4Credentials {
    const { ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET, ALIYUN_OSS_ENDPOINT } = this.env;
    if (!ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET || !ALIYUN_OSS_ENDPOINT) {
      throw new Error("BlogHTML Preview OSS credentials are not configured");
    }
    const region = this.env.ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
    if (!region) throw new Error("BlogHTML Preview OSS region is not configured");
    return { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region };
  }
}

async function readUtf8BodyWithinLimit(response: Response, maxBytes: number): Promise<string> {
  const contentLength = response.headers.get("content-length");
  if (contentLength && Number(contentLength) > maxBytes) {
    throw new Error("Preview object exceeds the configured limit");
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
      if (totalBytes > maxBytes) {
        await reader.cancel();
        throw new Error("Preview object exceeds the configured limit");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const body = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(body);
}
