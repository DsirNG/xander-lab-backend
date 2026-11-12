import { createOssObjectUrl, inferOssRegion, signedOssGetUrl } from "@dinqor/worker-runtime/oss";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";

/** Re-signs only this owner's generated image keys on configured media hosts. */
export class OssAgentImageUrlMapper {
  constructor(private readonly env: AgentEnvironment) {}

  async refresh(userId: number, contents: string[]): Promise<string[]> {
    const { ALIYUN_OSS_ENDPOINT: endpoint, ALIYUN_OSS_BUCKET: bucket,
      ALIYUN_OSS_ACCESS_KEY_ID: accessKeyId, ALIYUN_OSS_ACCESS_KEY_SECRET: accessKeySecret } = this.env;
    if (!endpoint || !bucket || !accessKeyId || !accessKeySecret) return contents;
    const region = this.env.ALIYUN_OSS_REGION ?? inferOssRegion(endpoint);
    if (!region) return contents;
    const prefix = this.env.AGENT_GENERATED_MEDIA_OSS_PREFIX ?? "photos/blog";
    const hosts = new Set(["dinqor-content-automation.dinqor.workers.dev"]);
    if (this.env.AGENT_MEDIA_PUBLIC_BASE_URL) hosts.add(new URL(this.env.AGENT_MEDIA_PUBLIC_BASE_URL).host);
    const buckets = [bucket, this.env.ALIYUN_OSS_LEGACY_BUCKET].filter((value): value is string => !!value);
    const objectHosts = new Map(buckets.map(value => [createOssObjectUrl(endpoint, "probe", value).host, value]));
    const urls = new Set(contents.flatMap(content => content.match(/https:\/\/[^\s"'<>\x60\\)\]]+/g) ?? []));
    const replacements = new Map<string, string>();
    const keyPattern = new RegExp("^" + userId + "/\\d{4}/(?:0[1-9]|1[0-2])/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg|webp|gif)$");
    // Local signing only: loading history never downloads the image bytes.
    for (const value of urls) {
      let url: URL;
      try { url = new URL(value); } catch { continue; }
      if (url.username || url.password) continue;
      let objectKey: string;
      let targetBucket = bucket;
      if (hosts.has(url.host) && url.pathname.startsWith("/api/public/blog/media/")) {
        objectKey = url.pathname.slice("/api/public/blog/media/".length);
      } else if (objectHosts.has(url.host) && url.pathname.startsWith("/" + prefix + "/")) {
        objectKey = url.pathname.slice(prefix.length + 2);
        targetBucket = objectHosts.get(url.host)!;
      } else continue;
      if (!keyPattern.test(objectKey)) continue;
      const signed = await signedOssGetUrl(createOssObjectUrl(endpoint, prefix + "/" + objectKey, targetBucket),
        { accessKeyId, accessKeySecret, region }, 86_400);
      replacements.set(value, signed.toString());
    }
    return contents.map(content => content.replace(/https:\/\/[^\s"'<>\x60\\)\]]+/g, value => replacements.get(value) ?? value));
  }
}
