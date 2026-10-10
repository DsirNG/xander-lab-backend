import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssGetHeaders,
  signedOssPutHeaders,
  type OssV4Credentials,
} from "@dinqor/worker-runtime/oss";
import type { PlatformFileObject, PlatformFileStorage } from "../interfaces/platform-file-storage.js";

export interface PlatformOssEnvironment {
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_BUCKET?: string;
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
    if (!response.ok) throw new Error("Platform file upload failed");
  }

  async read(objectKey: string, maxBytes: number): Promise<PlatformFileObject | null> {
    const url = this.objectUrl(objectKey);
    const headers = await signedOssGetHeaders(url, this.credentials());
    const response = await fetch(url, {
      method: "GET",
      headers,
      redirect: "manual",
      signal: AbortSignal.timeout(30_000),
    });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error("Platform file download failed");
    const contentLength = response.headers.get("content-length");
    if (contentLength && Number(contentLength) > maxBytes) throw new Error("Platform file exceeds its stored size");
    return { body: response.body, contentType: response.headers.get("content-type") };
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
    if (!response.ok && response.status !== 404) throw new Error("Platform file cleanup failed");
  }

  private objectUrl(objectKey: string): URL {
    if (!this.env.ALIYUN_OSS_ENDPOINT) throw new Error("Platform OSS endpoint is not configured");
    const prefix = this.env.PLATFORM_OSS_PREFIX ?? "platform-files";
    return createOssObjectUrl(this.env.ALIYUN_OSS_ENDPOINT, `${prefix}/${objectKey}`, this.env.ALIYUN_OSS_BUCKET);
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
