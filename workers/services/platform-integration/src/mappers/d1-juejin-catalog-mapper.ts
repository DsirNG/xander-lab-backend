import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  JuejinCatalogRepository,
  JuejinCategoryRow,
  JuejinTagRow,
} from "../interfaces/juejin-catalog-repository.js";

export class D1JuejinCatalogMapper implements JuejinCatalogRepository {
  constructor(private readonly db: D1Binding) {}

  async upsert(categories: JuejinCategoryRow[], tags: JuejinTagRow[]): Promise<void> {
    const statements = [
      ...categories.map((category) => this.db.prepare(`
        INSERT INTO juejin_category (category_id, category_name, rank)
        VALUES (?, ?, ?)
        ON CONFLICT(category_id) DO UPDATE SET
          category_name = excluded.category_name,
          rank = excluded.rank,
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      `).bind(category.categoryId, category.categoryName, category.rank)),
      ...tags.map((tag) => this.db.prepare(`
        INSERT INTO juejin_tag (tag_id, tag_name, category_id)
        VALUES (?, ?, ?)
        ON CONFLICT(tag_id) DO UPDATE SET
          tag_name = excluded.tag_name,
          category_id = COALESCE(excluded.category_id, juejin_tag.category_id),
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      `).bind(tag.tagId, tag.tagName, tag.categoryId)),
    ];
    for (let offset = 0; offset < statements.length; offset += 100) {
      const results = await this.db.batch(statements.slice(offset, offset + 100));
      if (results.some((result) => !result.success)) throw new Error("Juejin catalog persistence failed");
    }
  }

  async markSynced(): Promise<void> {
    const [result] = await this.db.batch([this.db.prepare(`
      INSERT INTO juejin_catalog_sync (id, synced_at)
      VALUES (1, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
      ON CONFLICT(id) DO UPDATE SET synced_at = excluded.synced_at
    `)]);
    if (!result?.success) throw new Error("Juejin catalog sync checkpoint failed");
  }

  async isFresh(freshnessHours: number): Promise<boolean> {
    if (!Number.isSafeInteger(freshnessHours) || freshnessHours < 1) return false;
    const result = await this.db.prepare(
      "SELECT synced_at AS syncedAt FROM juejin_catalog_sync WHERE id = 1",
    ).first<{ syncedAt: string }>();
    const cutoff = Date.now() - freshnessHours * 60 * 60 * 1000;
    const syncedAt = result?.syncedAt ? Date.parse(result.syncedAt) : NaN;
    return Number.isFinite(syncedAt) && syncedAt >= cutoff;
  }

  findCategoryId(categoryName: string): Promise<string | null> {
    return this.db.prepare(`
      SELECT category_id AS categoryId FROM juejin_category
       WHERE category_name = ? ORDER BY rank, category_id LIMIT 1
    `).bind(categoryName).first<{ categoryId: string }>().then((row) => row?.categoryId ?? null);
  }

  findTagId(tagName: string): Promise<string | null> {
    return this.db.prepare(`
      SELECT tag_id AS tagId FROM juejin_tag
       WHERE tag_name = ? ORDER BY tag_id LIMIT 1
    `).bind(tagName).first<{ tagId: string }>().then((row) => row?.tagId ?? null);
  }
}
