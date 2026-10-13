import type { JuejinPublicationPort } from "../interfaces/juejin-publication-port.js";
import type { JuejinCatalogService } from "./juejin-catalog-service.js";
import { DEFAULT_JUEJIN_TAG_ID } from "./juejin-catalog-service.js";
import type { PlatformCredentialService } from "./platform-credential-service.js";
import { PlatformCredentialError } from "./platform-credential-service.js";
import { JuejinCatalogProviderError } from "../interfaces/juejin-catalog-port.js";

export interface JuejinPublicationRequest {
  userId: number;
  postId: number;
  categoryId?: string | null;
  tagIds?: string[] | null;
  post: {
    id?: number | null;
    title: string;
    summary?: string | null;
    content: string;
    category?: string | null;
    categoryName?: string | null;
    coverImage?: string | null;
    tags?: string[] | null;
  };
}

const MAX_CONTENT_BYTES = 2 * 1024 * 1024;
const MAX_TAGS = 3;
const MIN_BRIEF_LENGTH = 50;
const MAX_BRIEF_LENGTH = 100;
const MARKDOWN_PREFIX = /^[#>*\-+\d.\s]+/;
const NUMERIC_ID = /^\d{1,20}$/;

export class JuejinPublicationService {
  constructor(
    private readonly credentials: PlatformCredentialService,
    private readonly catalog: JuejinCatalogService,
    private readonly provider: JuejinPublicationPort,
  ) {}

  async publish(request: JuejinPublicationRequest): Promise<{ platform: "juejin"; externalId: string; url: string }> {
    this.validate(request);
    const credential = await this.credentials.require(request.userId, "juejin");
    try {
      try {
        await this.catalog.ensureFresh(request.userId);
      } catch (error) {
        // Keep the legacy behavior: catalog outages do not block publishing when
        // cached categories/tags or the provider defaults are still usable.
        if (error instanceof PlatformCredentialError && error.status === 409) throw error;
      }

      const categoryCandidate = request.categoryId?.trim()
        || request.post.categoryName?.trim()
        || null;
      const categoryId = await this.catalog.resolveCategoryId(categoryCandidate);
      const tagIds = await this.resolveTags(request.tagIds ?? request.post.tags ?? []);
      const draftId = await this.provider.createDraft(credential, {
        title: request.post.title.trim(),
        briefContent: this.buildBrief(request.post.summary ?? "", request.post.content),
        markdownContent: request.post.content,
        categoryId,
        tagIds,
        coverImage: request.post.coverImage?.trim() ?? "",
      });
      if (!draftId) throw new PlatformCredentialError(502, "掘金草稿创建失败");
      const articleId = await this.provider.publishDraft(credential, draftId);
      if (!articleId) throw new PlatformCredentialError(502, "掘金文章发布失败");
      return { platform: "juejin", externalId: articleId, url: `https://juejin.cn/post/${encodeURIComponent(articleId)}` };
    } catch (error) {
      if (error instanceof JuejinCatalogProviderError) {
        if (error.sessionExpired) await this.credentials.markExpired(request.userId, "juejin");
        throw new PlatformCredentialError(error.sessionExpired ? 409 : 503,
          error.sessionExpired ? "掘金授权已失效，请重新授权" : "掘金发布服务暂不可用");
      }
      if (error instanceof PlatformCredentialError) throw error;
      throw new PlatformCredentialError(503, "掘金发布服务暂不可用");
    }
  }

  private async resolveTags(tags: string[]): Promise<string[]> {
    const resolved: string[] = [];
    for (const raw of tags) {
      if (typeof raw !== "string" || !raw.trim()) continue;
      const value = raw.trim();
      let id: string | null = NUMERIC_ID.test(value) ? value : await this.catalog.resolveTagId(value);
      if (!id && !NUMERIC_ID.test(value)) {
        try {
          const candidates = await this.provider.searchTags(value, 8);
          id = candidates.find((candidate) => candidate.name.toLocaleLowerCase() === value.toLocaleLowerCase())?.id
            ?? candidates[0]?.id ?? null;
        } catch {
          id = null;
        }
      }
      if (id && !resolved.includes(id)) resolved.push(id);
      if (resolved.length === MAX_TAGS) break;
    }
    return resolved.length ? resolved : [DEFAULT_JUEJIN_TAG_ID];
  }

  private buildBrief(summary: string, content: string): string {
    let brief = summary.trim() || this.firstParagraph(content) || content.trim() || "DinQorAI";
    if (brief.length > MAX_BRIEF_LENGTH) return brief.slice(0, MAX_BRIEF_LENGTH);
    if (brief.length < MIN_BRIEF_LENGTH) {
      const seed = brief || "DinQorAI";
      while (brief.length < MIN_BRIEF_LENGTH) brief += seed.slice(0, MIN_BRIEF_LENGTH - brief.length);
    }
    return brief;
  }

  private firstParagraph(content: string): string | null {
    for (const line of content.split(/\r\n|[\n\r\u2028\u2029]/)) {
      const cleaned = line.trim().replace(MARKDOWN_PREFIX, "").trim();
      if (cleaned.length >= 10) return cleaned;
    }
    return null;
  }

  private validate(request: JuejinPublicationRequest): void {
    if (!request || !Number.isSafeInteger(request.userId) || request.userId < 1
      || !Number.isSafeInteger(request.postId) || request.postId < 1
      || !request.post || typeof request.post !== "object" || Array.isArray(request.post)
      || (request.post.id !== undefined && request.post.id !== null && request.post.id !== request.postId)
      || typeof request.post.title !== "string" || !request.post.title.trim() || request.post.title.length > 300
      || typeof request.post.content !== "string" || !request.post.content.trim()
      || new TextEncoder().encode(request.post.content).byteLength > MAX_CONTENT_BYTES
      || (request.post.summary != null && typeof request.post.summary !== "string")
      || (request.post.category != null && (typeof request.post.category !== "string" || request.post.category.length > 200))
      || (request.post.categoryName != null && (typeof request.post.categoryName !== "string" || request.post.categoryName.length > 200))
      || (request.post.coverImage != null && (typeof request.post.coverImage !== "string" || request.post.coverImage.length > 2_000))
      || (request.categoryId != null && (typeof request.categoryId !== "string" || request.categoryId.length > 200))
      || (request.post.tags != null && (!Array.isArray(request.post.tags)
        || request.post.tags.length > 50 || request.post.tags.some((tag) => typeof tag !== "string" || tag.length > 200)))
      || (request.tagIds != null && (!Array.isArray(request.tagIds)
        || request.tagIds.length > 50 || request.tagIds.some((tag) => typeof tag !== "string" || tag.length > 200)))) {
      throw new PlatformCredentialError(400, "站外同步文章载荷非法");
    }
  }
}
