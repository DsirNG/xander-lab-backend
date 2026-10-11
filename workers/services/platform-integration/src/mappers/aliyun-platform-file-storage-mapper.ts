import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssHeadHeaders,
  signedOssGetHeaders,
  signedOssPutHeaders,
  type OssV4Credentials,
} from "@dinqor/worker-runtime/oss";
import {
  PlatformFileStorageError,
  type PlatformFileObject,
  type PlatformFileStorage,
} from "../interfaces/platform-file-storage.js";

function errorField(body: string, field: "Code"): string | undefined {
  const value = body.match(new RegExp(`<${field}>([^<]{1,100})</${field}>`, "i"))?.[1];
  return value && /^[a-z0-9._-]+$/i.test(value) ? value : undefined;
}

function safeRequestId(value: string | null): string | undefined {
  return value && /^[a-z0-9_-]{1,100}$/i.test(value) ? value : undefined;
}

export interface PlatformOssEnvironment {
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_LEGACY_BUCKET?: string;
  PLATFORM_OSS_PREFIX?: string;
}

/** Signs OSS operations for Platform-owned file objects. */
export class AliyunPlatformFileStorageMapper implements PlatformFileStorage {
  constructor(private readonly env: PlatformOssEnvironment) {}

  async put(objectKey: string, body: Uint8Array, contentType: string): Promise<void> {
    const url = this.objectUrl(objectKey);
    const headers = await signedOssPutHeaders(url, this.credentials(), contentType);
    const response = await fetch(url, {
      method: "PUT",
      headers,
      body: body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) as ArrayBuffer,
      redirect: "manual",
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) {
      const body = await response.text().catch(() => "");
      throw new PlatformFileStorageError(
        "put",
        response.status,
        errorField(body, "Code"),
        safeRequestId(response.headers.get("x-oss-request-id")),
      );
    }
  }

  async read(objectKey: string, maxBytes: number): Promise<PlatformFileObject | null> {
    const credentials = this.credentials();
    const buckets = this.bucketCandidates();
    for (const bucket of buckets) {
      const url = this.objectUrl(objectKey, bucket);
      const response = await fetch(url, {
        method: "GET",
        headers: await signedOssGetHeaders(url, credentials),
        redirect: "manual",
        signal: AbortSignal.timeout(30_000),
      });
      if (response.status === 404) continue;
      if (!response.ok) throw new Error("Platform file download failed");
      const contentLength = response.headers.get("content-length");
      if (contentLength && Number(contentLength) > maxBytes) throw new Error("Platform file exceeds its stored size");
      return { body: response.body, contentType: response.headers.get("content-type") };
    }
    return null;
  }

  async delete(objectKey: string): Promise<void> {
    const credentials = this.credentials();
    const buckets = this.bucketCandidates();
    for (const bucket of buckets) {
      const url = this.objectUrl(objectKey, bucket);
      if (buckets.length > 1) {
        const head = await fetch(url, {
          method: "HEAD",
          headers: await signedOssHeadHeaders(url, credentials),
          redirect: "manual",
          signal: AbortSignal.timeout(10_000),
        });
        if (head.status === 404) continue;
        if (!head.ok) throw new Error("Platform file cleanup failed");
      }
      const response = await fetch(url, {
        method: "DELETE",
        headers: await signedOssDeleteHeaders(url, credentials),
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
      if (response.status === 404) continue;
      if (!response.ok) throw new Error("Platform file cleanup failed");
      return;
    }
  }

  private bucketCandidates(): Array<string | undefined> {
    const current = this.env.ALIYUN_OSS_BUCKET;
    const legacy = this.env.ALIYUN_OSS_LEGACY_BUCKET;
    return legacy && legacy !== current ? [current, legacy] : [current];
  }

  private objectUrl(objectKey: string, bucket = this.env.ALIYUN_OSS_BUCKET): URL {
    if (!this.env.ALIYUN_OSS_ENDPOINT) throw new Error("Platform OSS endpoint is not configured");
    const prefix = this.env.PLATFORM_OSS_PREFIX ?? "platform-files";
    return createOssObjectUrl(this.env.ALIYUN_OSS_ENDPOINT, `${prefix}/${objectKey}`, bucket);
  }

  private credentials(): OssV4Credentials {
    const { ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET, ALIYUN_OSS_ENDPOINT } = this.env;
    if (!ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET || !ALIYUN_OSS_ENDPOINT) {
      throw new Error("Platform OSS credentials are not configured");
    }
    const region = this.env.ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
    if (!region) throw new Error("Platform OSS region is not configured");
    return { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region };
  }
}
