export interface JuejinCategory {
  id: string;
  name: string;
  rank: number;
  hotTags: Array<{ id: string; name: string }>;
}

export interface JuejinTagPage {
  tags: Array<{ id: string; name: string }>;
  hasMore: boolean;
  cursor: string;
}

export class JuejinCatalogProviderError extends Error {
  constructor(readonly sessionExpired: boolean, message = "掘金目录服务暂不可用") {
    super(message);
    this.name = "JuejinCatalogProviderError";
  }
}

export interface JuejinCatalogPort {
  queryCategories(credential: Record<string, string>): Promise<JuejinCategory[]>;
  queryTags(
    credential: Record<string, string>,
    cursor: string,
    limit: number,
  ): Promise<JuejinTagPage>;
}
