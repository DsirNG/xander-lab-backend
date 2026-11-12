import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssGetHeaders,
  signedOssGetUrl,
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
  constructor(
    private readonly env: BlogMediaOssEnvironment,
    private readonly pause: (milliseconds: number) => Promise<void> =
      milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)),
  ) {}

  async upload(objectKey: string, bytes: Uint8Array, contentType: string): Promise<StoredBlogMedia> {
    const url = this.publicObjectUrl(objectKey);
    await this.put(objectKey, bytes, contentType);
    return { url: url.toString(), storedName: objectKey };
  }

  async signedUrl(objectKey: string): Promise<string> {
    const { objectUrl, credentials } = this.privateObject(objectKey);
    return (await signedOssGetUrl(objectUrl, credentials, 86_400)).toString();
  }

  private async put(objectKey: string, bytes: Uint8Array, contentType: string): Promise<void> {
    const { objectUrl, credentials } = this.privateObject(objectKey);
    const body = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(body).set(bytes);
    const startedAt = Date.now();
    const timeoutMs = bytes.byteLength <= 256 * 1024 ? 8_000 : 20_000;
    const attempts = 2;
    for (let attempt = 1; attempt <= attempts; attempt++) {
      let response: Response;
      try {
        // Each attempt re-signs a replayable buffer at the same random object key.
        // This PUT is idempotent even if OSS saved it before the first connection timed out.
        response = await fetch(objectUrl, {
          method: "PUT",
          headers: await signedOssPutHeaders(objectUrl, credentials, contentType),
          body,
          redirect: "manual",
          signal: AbortSignal.timeout(timeoutMs),
        });
      } catch (error) {
        const transient = error instanceof Error
          && ["TimeoutError", "AbortError", "TypeError", "NetworkError"].includes(error.name);
        if (transient && attempt < attempts) {
          console.warn("content.media.oss-put-retry", {
            attempt, nextAttempt: attempt + 1, error: error.name,
            bytes: bytes.byteLength, elapsedMs: Date.now() - startedAt, timeoutMs,
          });
          await this.pause(150);
          continue;
        }
        console.error("Blog media OSS request unavailable", {
          method: "PUT", error: error instanceof Error ? error.name : "UnknownError",
          attempts: attempt, elapsedMs: Date.now() - startedAt, bytes: bytes.byteLength,
        });
        throw new Error("Blog media storage is unavailable");
      }
      if (response.ok) {
        await response.body?.cancel().catch(() => {});
        console.info("content.media.oss-put-completed", {
          attempt, elapsedMs: Date.now() - startedAt, bytes: bytes.byteLength,
        });
        return;
      }
      const retryableStatus = [408, 429, 500, 502, 503, 504].includes(response.status);
      if (retryableStatus && attempt < attempts) {
        const status = response.status;
        await response.body?.cancel().catch(() => {});
        console.warn("content.media.oss-put-retry", {
          attempt, nextAttempt: attempt + 1, status,
          bytes: bytes.byteLength, elapsedMs: Date.now() - startedAt, timeoutMs,
        });
        await this.pause(150);
        continue;
      }
      // Authentication, signature and validation failures must be reported, not replayed.
      const errorBody = await response.text().catch(() => "");
      const errorCode = response.headers.get("x-oss-ec")
        ?? errorBody.match(/<Code>\s*([A-Za-z0-9_-]{1,80})\s*<\/Code>/i)?.[1]
        ?? null;
      console.error("Blog media OSS PUT rejected", {
        status: response.status,
        errorCode: errorCode?.match(/^[a-z0-9._-]{1,100}$/i)?.[0],
        requestId: response.headers.get("x-oss-request-id")?.match(/^[a-z0-9_-]{1,100}$/i)?.[0],
      });
      throw new Error("Blog media upload failed");
    }
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
        // A signed ranged GET uses the same verified read path as public images.
        const headers = await signedOssGetHeaders(objectUrl, credentials);
        headers.set("range", "bytes=0-0");
        const head = await this.request(objectUrl, {
          method: "GET",
          headers,
          redirect: "manual",
          signal: AbortSignal.timeout(10_000),
        });
        await head.body?.cancel().catch(() => {});
        if (head.status === 404) continue;
        if (!head.ok) {
          console.error("content.media.cleanup-probe-failed", { status: head.status });
          throw new Error("Blog media cleanup failed");
        }
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
