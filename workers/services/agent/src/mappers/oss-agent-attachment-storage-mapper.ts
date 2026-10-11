import {
  createOssObjectUrl,
  inferOssRegion,
  signedOssDeleteHeaders,
  signedOssGetUrl,
  signedOssPutHeaders,
} from "@dinqor/worker-runtime/oss";
import type { OssV4Credentials } from "@dinqor/worker-runtime/oss";
import type { AgentAttachmentStorage } from "../interfaces/agent-attachment.js";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";

function safeOssErrorCode(value: string | null): string | undefined {
  return value && /^[a-z0-9._-]{1,100}$/i.test(value) ? value : undefined;
}

function safeOssRequestId(value: string | null): string | undefined {
  return value && /^[a-z0-9_-]{1,100}$/i.test(value) ? value : undefined;
}

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
    if (!response.ok) {
      const body = await response.text().catch(() => "");
      const errorCode = response.headers.get("x-oss-ec")
        ?? body.match(/<Code>\s*([A-Za-z0-9_-]{1,80})\s*<\/Code>/i)?.[1]
        ?? null;
      console.error("Agent attachment OSS PUT rejected", {
        status: response.status,
        errorCode: safeOssErrorCode(errorCode),
        requestId: safeOssRequestId(response.headers.get("x-oss-request-id")),
      });
      throw new Error("Agent attachment upload failed");
    }
  }

  async delete(objectKey: string): Promise<void> {
    const credentials = this.credentials();
    const buckets = this.bucketCandidates();
    for (const bucket of buckets) {
      const { objectUrl } = this.object(objectKey, bucket);
      if (buckets.length > 1) {
        if (!await this.objectExists(objectUrl, credentials, bucket)) continue;
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
        if (!await this.objectExists(objectUrl, credentials, bucket)) continue;
      }
      return (await signedOssGetUrl(objectUrl, credentials, 600)).toString();
    }
    throw new Error("Agent attachment fetch failed");
  }

  private async request(url: URL, init: RequestInit): Promise<Response> {
    try {
      return await fetch(url, init);
    } catch (error) {
      console.error("Agent attachment OSS request unavailable", {
        method: init.method ?? "GET",
        error: error instanceof Error ? error.name : "UnknownError",
      });
      throw new Error("Agent attachment storage is unavailable");
    }
  }

  private async objectExists(
    objectUrl: URL,
    credentials: OssV4Credentials,
    bucket: string | undefined,
  ): Promise<boolean> {
    const probeUrl = await signedOssGetUrl(objectUrl, credentials, 60);
    const response = await this.request(probeUrl, {
      method: "GET",
      headers: { range: "bytes=0-0" },
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (response.status === 404) {
      await response.body?.cancel().catch(() => {});
      return false;
    }
    if (!response.ok) {
      const body = await response.text().catch(() => "");
      const errorCode = response.headers.get("x-oss-ec")
        ?? body.match(/<Code>\s*([A-Za-z0-9_-]{1,80})\s*<\/Code>/i)?.[1]
        ?? null;
      console.error("Agent attachment OSS existence probe rejected", {
        bucket,
        status: response.status,
        errorCode: safeOssErrorCode(errorCode),
        requestId: safeOssRequestId(response.headers.get("x-oss-request-id")),
      });
      throw new Error("Agent attachment fetch failed");
    }
    await response.body?.cancel().catch(() => {});
    return true;
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
