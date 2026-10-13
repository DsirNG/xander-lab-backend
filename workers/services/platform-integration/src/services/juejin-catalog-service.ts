import type { JuejinCatalogPort } from "../interfaces/juejin-catalog-port.js";
import { JuejinCatalogProviderError } from "../interfaces/juejin-catalog-port.js";
import type {
  JuejinCatalogRepository,
  JuejinCategoryRow,
  JuejinTagRow,
} from "../interfaces/juejin-catalog-repository.js";
import type { PlatformCredentialService } from "./platform-credential-service.js";
import { PlatformCredentialError } from "./platform-credential-service.js";

export const DEFAULT_JUEJIN_CATEGORY_ID = "6809637767543259144";
export const DEFAULT_JUEJIN_TAG_ID = "6809640407484334093";
const TAG_PAGE_SIZE = 100;
const MAX_TAG_PAGES = 10;

export class JuejinCatalogService {
  constructor(
    private readonly credentials: PlatformCredentialService,
    private readonly catalog: JuejinCatalogRepository,
    private readonly provider: JuejinCatalogPort,
    private readonly freshnessHours = 24,
  ) {}

  async sync(userId: number): Promise<void> {
    const credential = await this.credentials.require(userId, "juejin");
    const categoryRows = new Map<string, JuejinCategoryRow>();
    const tagRows = new Map<string, JuejinTagRow>();
    try {
      for (const category of await this.provider.queryCategories(credential)) {
        categoryRows.set(category.id, {
          categoryId: category.id,
          categoryName: category.name,
          rank: category.rank,
        });
        for (const tag of category.hotTags) {
          tagRows.set(tag.id, { tagId: tag.id, tagName: tag.name, categoryId: category.id });
        }
      }
      let cursor = "0";
      const seenCursors = new Set([cursor]);
      for (let page = 0; page < MAX_TAG_PAGES; page += 1) {
        const result = await this.provider.queryTags(credential, cursor, TAG_PAGE_SIZE);
        for (const tag of result.tags) {
          const prior = tagRows.get(tag.id);
          tagRows.set(tag.id, {
            tagId: tag.id,
            tagName: tag.name,
            categoryId: prior?.categoryId ?? null,
          });
        }
        if (!result.hasMore) break;
        if (!result.cursor || seenCursors.has(result.cursor)) throw new Error("掘金目录游标未推进");
        cursor = result.cursor;
        seenCursors.add(cursor);
      }
    } catch (error) {
      if (error instanceof JuejinCatalogProviderError) {
        if (error.sessionExpired) await this.credentials.markExpired(userId, "juejin");
        throw new PlatformCredentialError(error.sessionExpired ? 409 : 503,
          error.sessionExpired ? "掘金授权已失效，请重新授权" : "掘金目录同步暂不可用");
      }
      if (error instanceof PlatformCredentialError) throw error;
      throw new PlatformCredentialError(503, "掘金目录同步暂不可用");
    }
    if (categoryRows.size === 0 && tagRows.size === 0) {
      throw new PlatformCredentialError(503, "掘金目录返回空数据");
    }
    await this.catalog.upsert([...categoryRows.values()], [...tagRows.values()]);
    await this.catalog.markSynced();
  }

  async ensureFresh(userId: number): Promise<void> {
    if (!await this.catalog.isFresh(this.freshnessHours)) await this.sync(userId);
  }

  isFresh(): Promise<boolean> {
    return this.catalog.isFresh(this.freshnessHours);
  }

  async resolveCategoryId(categoryName: string | null): Promise<string> {
    if (categoryName && /^\d{1,20}$/.test(categoryName)) return categoryName;
    if (categoryName?.trim()) {
      const resolved = await this.catalog.findCategoryId(categoryName.trim());
      if (resolved) return resolved;
    }
    return DEFAULT_JUEJIN_CATEGORY_ID;
  }

  resolveTagId(tagName: string): Promise<string | null> {
    return this.catalog.findTagId(tagName.trim());
  }
}
