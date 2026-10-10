import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssPutHeaders,
} from "@dinqor/worker-runtime/oss";
import type { BlogMediaStorage, StoredBlogMedia } from "../interfaces/blog-media-storage.js";

interface BlogMediaOssEnvironment {
  CONTENT_OSS_PUBLIC_BASE_URL?: string;
  CONTENT_MEDIA_OSS_OBJECT_PREFIX?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
}

function safeSegments(value: string): string[] {
  const segments = value.split("/");
  if (segments.some((part) => !part || part === "." || part === ".." || part.includes("\\"))) {
    throw new Error("Invalid blog media object key");
  }
  return segments;
}

export class OssBlogMediaMapper implements BlogMediaStorage {
  constructor(private readonly env: BlogMediaOssEnvironment) {}

  async upload(objectKey: string, bytes: Uint8Array, contentType: string): Promise<StoredBlogMedia> {
    const url = this.publicObjectUrl(objectKey);
    const { objectUrl, credentials } = this.privateObject(objectKey);
    const body = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(body).set(bytes);
    const response = await this.request(objectUrl, {
      method: "PUT",
      headers: await signedOssPutHeaders(objectUrl, credentials, contentType),
      body,
      redirect: "manual",
      signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) throw new Error("Blog media upload failed");
    return { url: url.toString(), storedName: objectKey };
  }

  async delete(objectKey: string): Promise<void> {
    const { objectUrl, credentials } = this.privateObject(objectKey);
    const response = await this.request(objectUrl, {
      method: "DELETE",
      headers: await signedOssDeleteHeaders(objectUrl, credentials),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok && response.status !== 404) throw new Error("Blog media cleanup failed");
  }

  private async request(url: URL, init: RequestInit): Promise<Response> {
    try { return await fetch(url, init); } catch { throw new Error("Blog media storage is unavailable"); }
  }

  private privateObject(objectKey: string) {
    const {
      ALIYUN_OSS_ENDPOINT, ALIYUN_OSS_BUCKET, ALIYUN_OSS_REGION,
      ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET,
    } = this.env;
    if (!ALIYUN_OSS_ENDPOINT || !ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET) {
      throw new Error("Blog media OSS configuration is incomplete");
    }
    const region = ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
    if (!region) throw new Error("Blog media OSS region is required for a custom endpoint");
    const prefix = this.env.CONTENT_MEDIA_OSS_OBJECT_PREFIX ?? "photos/blog";
    const key = [...safeSegments(prefix), ...safeSegments(objectKey)].join("/");
    return {
      objectUrl: createOssObjectUrl(ALIYUN_OSS_ENDPOINT, key, ALIYUN_OSS_BUCKET),
      credentials: { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region },
    };
  }

  private publicObjectUrl(objectKey: string): URL {
    if (!this.env.CONTENT_OSS_PUBLIC_BASE_URL) throw new Error("Content OSS public base URL is not configured");
    const base = new URL(this.env.CONTENT_OSS_PUBLIC_BASE_URL);
    if (base.protocol !== "https:" || base.username || base.password || base.search || base.hash) {
      throw new Error("Content OSS public base URL must be a clean HTTPS URL");
    }
    const prefix = this.env.CONTENT_MEDIA_OSS_OBJECT_PREFIX ?? "photos/blog";
    const segments = [...safeSegments(prefix), ...safeSegments(objectKey)];
    const basePath = base.pathname.replace(/\/+$/, "");
    base.pathname = `${basePath}/${segments.map(encodeURIComponent).join("/")}`;
    return base;
  }
}
