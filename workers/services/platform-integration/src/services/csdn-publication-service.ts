import { CsdnProviderError, type CsdnArticleInput, type CsdnPublicationPort } from "../interfaces/csdn-publication-port.js";
import type { PlatformCredentialService } from "./platform-credential-service.js";
import { PlatformCredentialError } from "./platform-credential-service.js";

const MAX_CONTENT_BYTES = 2 * 1024 * 1024;

export class CsdnPublicationService {
  constructor(
    private readonly credentials: PlatformCredentialService,
    private readonly provider: CsdnPublicationPort,
  ) {}

  async publish(input: CsdnArticleInput): Promise<{ platform: "csdn"; externalId: string; url: string }> {
    this.validate(input);
    const credential = await this.credentials.require(input.userId, "csdn");
    try {
      const result = await this.provider.publish(credential, input);
      return { platform: "csdn", ...result };
    } catch (error) {
      if (error instanceof CsdnProviderError && error.sessionExpired) {
        await this.credentials.markExpired(input.userId, "csdn");
        throw new PlatformCredentialError(409, "CSDN 授权已失效，请重新授权");
      }
      if (error instanceof PlatformCredentialError) throw error;
      throw new PlatformCredentialError(503, "CSDN 发布服务暂不可用");
    }
  }

  private validate(input: CsdnArticleInput): void {
    if (!input || !Number.isSafeInteger(input.userId) || input.userId < 1
      || !Number.isSafeInteger(input.postId) || input.postId < 1
      || typeof input.title !== "string" || !input.title.trim() || input.title.length > 300
      || typeof input.content !== "string" || !input.content.trim()
      || new TextEncoder().encode(input.content).byteLength > MAX_CONTENT_BYTES
      || typeof input.summary !== "string" || input.summary.length > 2_000
      || (input.category !== null && (typeof input.category !== "string" || input.category.length > 200))
      || (input.coverImage !== null && (typeof input.coverImage !== "string" || input.coverImage.length > 2_000))
      || !Array.isArray(input.tags) || input.tags.length > 50
      || input.tags.some((tag) => typeof tag !== "string" || tag.length > 200)) {
      throw new PlatformCredentialError(400, "CSDN 文章载荷非法");
    }
  }
}
