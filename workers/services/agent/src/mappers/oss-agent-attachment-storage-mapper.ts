import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssGetUrl,
  signedOssPutHeaders,
} from "@dinqor/worker-runtime/oss";
import type { AgentAttachmentStorage } from "../interfaces/agent-attachment.js";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";

export class OssAgentAttachmentStorageMapper implements AgentAttachmentStorage {
  constructor(private readonly env: AgentEnvironment) {}

  async write(objectKey: string, bytes: Uint8Array, contentType: string): Promise<void> {
    const { objectUrl, credentials } = this.object(objectKey);
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
    const { objectUrl, credentials } = this.object(objectKey);
    const response = await this.request(objectUrl, {
      method: "DELETE",
      headers: await signedOssDeleteHeaders(objectUrl, credentials),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok && response.status !== 404) throw new Error("Agent attachment cleanup failed");
  }

  async signedReadUrl(objectKey: string): Promise<string> {
    const { objectUrl, credentials } = this.object(objectKey);
    return (await signedOssGetUrl(objectUrl, credentials, 600)).toString();
  }

  private async request(url: URL, init: RequestInit): Promise<Response> {
    try {
      return await fetch(url, init);
    } catch {
      throw new Error("Agent attachment storage is unavailable");
    }
  }

  private object(objectKey: string) {
    const { ALIYUN_OSS_ACCESS_KEY_ID, ALIYUN_OSS_ACCESS_KEY_SECRET, ALIYUN_OSS_ENDPOINT } = this.env;
    if (!ALIYUN_OSS_ACCESS_KEY_ID || !ALIYUN_OSS_ACCESS_KEY_SECRET || !ALIYUN_OSS_ENDPOINT) {
      throw new Error("Agent Aliyun OSS configuration is incomplete");
    }
    const region = this.env.ALIYUN_OSS_REGION ?? inferOssRegion(ALIYUN_OSS_ENDPOINT);
    if (!region) throw new Error("Agent Aliyun OSS region is required for a custom endpoint");
    const prefix = this.env.AGENT_OSS_OBJECT_PREFIX ?? "agent-attachments";
    return {
      objectUrl: createOssObjectUrl(ALIYUN_OSS_ENDPOINT, `${prefix}/${objectKey}`, this.env.ALIYUN_OSS_BUCKET),
      credentials: { accessKeyId: ALIYUN_OSS_ACCESS_KEY_ID, accessKeySecret: ALIYUN_OSS_ACCESS_KEY_SECRET, region },
    };
  }
}
