import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssHeadHeaders,
  signedOssGetHeaders,
  signedOssPutHeaders,
} from "@dinqor/worker-runtime/oss";
import type { BlogMediaStorage, StoredBlogMedia } from "../interfaces/blog-media-storage.js";

interface BlogMediaOssEnvironment {
  CONTENT_OSS_PUBLIC_BASE_URL?: string;
  CONTENT_MEDIA_PUBLIC_BASE_URL?: string;
  CONTENT_MEDIA_OSS_OBJECT_PREFIX?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_LEGACY_BUCKET?: string;
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

  async read(objectKey: string): Promise<{ body: ReadableStream<Uint8Array> } | null> {
    for (const bucket of this.bucketCandidates()) {
      const { objectUrl, credentials } = this.privateObject(objectKey, bucket);
      const response = await this.request(objectUrl, {
        method: "GET",
        headers: await signedOssGetHeaders(objectUrl, credentials),
        redirect: "manual",
        signal: AbortSignal.timeout(15_000),
      });
      if (response.status === 404) continue;
      if (!response.ok || !response.body) throw new Error("Blog media fetch failed");
      return { body: response.body };
    }
    return null;
  }

  async delete(objectKey: string): Promise<void> {
    const buckets = this.bucketCandidates();
    for (const bucket of buckets) {
      const { objectUrl, credentials } = this.privateObject(objectKey, bucket);
      if (buckets.length > 1) {
        const head = await this.request(objectUrl, {
          method: "HEAD",
          headers: await signedOssHeadHeaders(objectUrl, credentials),
          redirect: "manual",
          signal: AbortSignal.timeout(10_000),
        });
        if (head.status === 404) continue;
        if (!head.ok) throw new Error("Blog media cleanup failed");
      }
      const response = await this.request(objectUrl, {
        method: "DELETE",
        headers: await signedOssDeleteHeaders(objectUrl, credentials),
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
      if (response.status === 404) continue;
      if (!response.ok) throw new Error("Blog media cleanup failed");
      return;
    }
  }

  private async request(url: URL, init: RequestInit): Promise<Response> {
    try { return await fetch(url, init); } catch { throw new Error("Blog media storage is unavailable"); }
  }

  private bucketCandidates(): Array<string | undefined> {
    const current = this.env.ALIYUN_OSS_BUCKET;
    const legacy = this.env.ALIYUN_OSS_LEGACY_BUCKET;
    return legacy && legacy !== current ? [current, legacy] : [current];
  }

  private privateObject(objectKey: string, bucket = this.env.ALIYUN_OSS_BUCKET) {
    const {
      ALIYUN_OSS_ENDPOINT, ALIYUN_OSS_REGION,
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
      objectUrl: createOssObjectUrl(ALIYUN_OSS_ENDPOINT, key, bucket),
      credentials: { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region },
    };
  }

  private publicObjectUrl(objectKey: string): URL {
    const publicBaseUrl = this.env.CONTENT_MEDIA_PUBLIC_BASE_URL ?? this.env.CONTENT_OSS_PUBLIC_BASE_URL;
    if (!publicBaseUrl) throw new Error("Content OSS public base URL is not configured");
    const base = new URL(publicBaseUrl);
    const localHttp = base.protocol === "http:" && ["localhost", "127.0.0.1"].includes(base.hostname);
    if ((!localHttp && base.protocol !== "https:") || base.username || base.password || base.search || base.hash) {
      throw new Error("Content media public base URL must be HTTPS or a local HTTP URL");
    }
    const prefix = this.env.CONTENT_MEDIA_OSS_OBJECT_PREFIX ?? "photos/blog";
    const segments = this.env.CONTENT_MEDIA_PUBLIC_BASE_URL
      ? safeSegments(objectKey)
      : [...safeSegments(prefix), ...safeSegments(objectKey)];
    const basePath = base.pathname.replace(/\/+$/, "");
    base.pathname = `${basePath}/${segments.map(encodeURIComponent).join("/")}`;
    return base;
  }
}
