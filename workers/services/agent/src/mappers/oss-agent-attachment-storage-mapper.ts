import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssHeadHeaders,
  signedOssGetUrl,
  signedOssPutHeaders,
} from "@dinqor/worker-runtime/oss";
import type { AgentAttachmentStorage } from "../interfaces/agent-attachment.js";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";

export class OssAgentAttachmentStorageMapper implements AgentAttachmentStorage {
  constructor(private readonly env: AgentEnvironment) {}

  async write(objectKey: string, bytes: Uint8Array, contentType: string): Promise<void> {
    const { objectUrl } = this.object(objectKey);
    const credentials = this.credentials();
    const body = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(body).set(bytes);
    const response = await this.request(objectUrl, {
      method: "PUT",
      headers: await signedOssPutHeaders(objectUrl, credentials, contentType),
      body,
      redirect: "manual",
      signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) throw new Error("Agent attachment upload failed");
  }

  async delete(objectKey: string): Promise<void> {
    const credentials = this.credentials();
    const buckets = this.bucketCandidates();
    for (const bucket of buckets) {
      const { objectUrl } = this.object(objectKey, bucket);
      if (buckets.length > 1) {
        const head = await this.request(objectUrl, {
          method: "HEAD",
          headers: await signedOssHeadHeaders(objectUrl, credentials),
          redirect: "manual",
          signal: AbortSignal.timeout(10_000),
        });
        if (head.status === 404) continue;
        if (!head.ok) throw new Error("Agent attachment cleanup failed");
      }
      const response = await this.request(objectUrl, {
        method: "DELETE",
        headers: await signedOssDeleteHeaders(objectUrl, credentials),
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
      if (response.status === 404) continue;
      if (!response.ok) throw new Error("Agent attachment cleanup failed");
      return;
    }
  }

  async signedReadUrl(objectKey: string): Promise<string> {
    const credentials = this.credentials();
    const buckets = this.bucketCandidates();
    for (const bucket of buckets) {
      const { objectUrl } = this.object(objectKey, bucket);
      if (buckets.length > 1) {
        const response = await this.request(objectUrl, {
          method: "HEAD",
          headers: await signedOssHeadHeaders(objectUrl, credentials),
          redirect: "manual",
          signal: AbortSignal.timeout(10_000),
        });
        if (response.status === 404) continue;
        if (!response.ok) throw new Error("Agent attachment fetch failed");
      }
      return (await signedOssGetUrl(objectUrl, credentials, 600)).toString();
    }
    throw new Error("Agent attachment fetch failed");
  }

  private async request(url: URL, init: RequestInit): Promise<Response> {
    try {
      return await fetch(url, init);
    } catch {
      throw new Error("Agent attachment storage is unavailable");
    }
  }

  private bucketCandidates(): Array<string | undefined> {
    const current = this.env.ALIYUN_OSS_BUCKET;
    const legacy = this.env.ALIYUN_OSS_LEGACY_BUCKET;
    return legacy && legacy !== current ? [current, legacy] : [current];
  }

  private credentials() {
    const { ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET, ALIYUN_OSS_ENDPOINT } = this.env;
    if (!ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET || !ALIYUN_OSS_ENDPOINT) {
      throw new Error("Agent Aliyun OSS configuration is incomplete");
    }
    const region = this.env.ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
    if (!region) throw new Error("Agent Aliyun OSS region is required for a custom endpoint");
    return { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region };
  }

  private object(objectKey: string, bucket = this.env.ALIYUN_OSS_BUCKET) {
    const { ALIYUN_OSS_ENDPOINT } = this.env;
    if (!ALIYUN_OSS_ENDPOINT) throw new Error("Agent Aliyun OSS endpoint is not configured");
    const prefix = this.env.AGENT_OSS_OBJECT_PREFIX ?? "agent-attachments";
    return {
      objectUrl: createOssObjectUrl(ALIYUN_OSS_ENDPOINT, `${prefix}/${objectKey}`, bucket),
    };
  }
}
