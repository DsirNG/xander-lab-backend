export interface JuejinCategoryRow {
  categoryId: string;
  categoryName: string;
  rank: number;
}

export interface JuejinTagRow {
  tagId: string;
  tagName: string;
  categoryId: string | null;
}

export interface JuejinCatalogRepository {
  upsert(categories: JuejinCategoryRow[], tags: JuejinTagRow[]): Promise<void>;
  markSynced(): Promise<void>;
  isFresh(freshnessHours: number): Promise<boolean>;
  findCategoryId(categoryName: string): Promise<string | null>;
  findTagId(tagName: string): Promise<string | null>;
}
