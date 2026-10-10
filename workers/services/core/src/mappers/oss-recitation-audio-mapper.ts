import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssGetUrl,
  signedOssPutHeaders,
} from "@dinqor/worker-runtime/oss";
import type { RecitationAudioStorage } from "../interfaces/recitation-audio.js";

interface RecitationOssEnvironment {
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
  RECITATION_OSS_OBJECT_PREFIX?: string;
}

export class OssRecitationAudioMapper implements RecitationAudioStorage {
  constructor(private readonly env: RecitationOssEnvironment) {}

  async write(objectKey: string, bytes: Uint8Array, contentType: string): Promise<void> {
    const { url, credentials } = this.object(objectKey);
    const body = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(body).set(bytes);
    const response = await this.request(url, {
      method: "PUT", headers: await signedOssPutHeaders(url, credentials, contentType), body,
      redirect: "manual", signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) throw new Error("Recitation audio upload failed");
  }

  async delete(objectKey: string): Promise<void> {
    const { url, credentials } = this.object(objectKey);
    const response = await this.request(url, {
      method: "DELETE", headers: await signedOssDeleteHeaders(url, credentials),
      redirect: "manual", signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok && response.status !== 404) throw new Error("Recitation audio cleanup failed");
  }

  async readUrl(objectKey: string): Promise<string> {
    const { url, credentials } = this.object(objectKey);
    return (await signedOssGetUrl(url, credentials, 600)).toString();
  }

  private async request(url: URL, init: RequestInit): Promise<Response> {
    try { return await fetch(url, init); } catch { throw new Error("Recitation audio storage is unavailable"); }
  }

  private object(objectKey: string) {
    const { ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET, ALIYUN_OSS_ENDPOINT } = this.env;
    if (!ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET || !ALIYUN_OSS_ENDPOINT) {
      throw new Error("Recitation OSS configuration is incomplete");
    }
    const region = this.env.ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
    if (!region) throw new Error("Recitation OSS region is required for a custom endpoint");
    const prefix = this.env.RECITATION_OSS_OBJECT_PREFIX ?? "recitations";
    return {
      url: createOssObjectUrl(ALIYUN_OSS_ENDPOINT, `${prefix}/${objectKey}`, this.env.ALIYUN_OSS_BUCKET),
      credentials: { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region },
    };
  }
}
