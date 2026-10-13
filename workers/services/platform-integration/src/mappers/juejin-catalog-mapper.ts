import type {
  JuejinCatalogPort,
  JuejinCategory,
  JuejinTagPage,
} from "../interfaces/juejin-catalog-port.js";
import type { JuejinDraftInput, JuejinPublicationPort, JuejinTagCandidate } from "../interfaces/juejin-publication-port.js";
import { JuejinCatalogProviderError as ProviderError } from "../interfaces/juejin-catalog-port.js";
import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import type { PlatformAccountPort, PlatformAccountProfile } from "../interfaces/platform-account-port.js";

const DEFAULT_BASE_URL = "https://api.juejin.cn";
const MAX_RESPONSE_BYTES = 1024 * 1024;

interface Credentials {
  cookie?: string;
  csrfToken?: string;
  aid?: string;
  uuid?: string;
}

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

function exactId(value: unknown): string {
  if (typeof value === "string" && /^\d{1,20}$/.test(value)) return value;
  if (typeof value === "number" && Number.isSafeInteger(value) && value >= 0) return String(value);
  throw new ProviderError(false, "掘金目录返回了无效 ID");
}

function name(value: unknown): string {
  if (typeof value !== "string" || !value.trim() || value.trim().length > 200) {
    throw new ProviderError(false, "掘金目录返回了无效名称");
  }
  return value.trim();
}

async function readProviderBody(response: Response): Promise<string> {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new ProviderError(false, "掘金接口响应过大");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new ProviderError(false, "掘金接口响应格式无效");
  }
}

export class JuejinCatalogMapper implements JuejinCatalogPort, JuejinPublicationPort, PlatformAccountPort {
  constructor(private readonly env: PlatformEnvironment) {}

  async getCurrentAccount(credential: Record<string, string>): Promise<PlatformAccountProfile> {
    const fields = credential as Credentials;
    if (!fields.cookie || /[\r\n]/.test(fields.cookie)
      || (fields.csrfToken && /[\r\n]/.test(fields.csrfToken))) throw new ProviderError(true, "掘金授权已失效");
    const endpoint = this.endpoint("/user_api/v1/user/get");
    endpoint.searchParams.set("aid", fields.aid?.trim() || this.env.JUEJIN_DEFAULT_AID?.trim() || "2608");
    endpoint.searchParams.set("uuid", fields.uuid?.trim() || "");
    let root: Record<string, unknown>;
    try {
      // Omit user_id: only the saved session may identify the current user.
      const response = await fetch(endpoint, {
        method: "GET", headers: {
          cookie: fields.cookie, origin: "https://juejin.cn", referer: "https://juejin.cn/",
          ...(fields.csrfToken ? { "x-secsdk-csrf-token": fields.csrfToken } : {}),
        }, redirect: "manual", signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) {
        await response.body?.cancel();
        throw new ProviderError(response.status === 401, "掘金账号接口暂不可用");
      }
      root = this.parseRoot(await readProviderBody(response));
    } catch (error) {
      if (error instanceof ProviderError) throw error;
      throw new ProviderError(false, "掘金账号接口暂不可用");
    }
    if (Number(root.err_no) !== 0) throw new ProviderError(false, "掘金账号信息格式无效");
    // Juejin returns success with null data for an anonymous current-account query.
    if (root.data === null) throw new ProviderError(true, "掘金授权已失效");
    const data = record(root.data);
    const id = exactId(data?.user_id);
    if (id === "0") throw new ProviderError(false, "掘金账号信息格式无效");
    const nickname = name(data?.user_name);
    let avatarUrl: string | null = null;
    if (typeof data?.avatar_large === "string") {
      try {
        const url = new URL(data.avatar_large);
        if (url.protocol === "https:" && !url.username && !url.password) avatarUrl = url.href;
      } catch { /* An optional avatar must not invalidate an authenticated account. */ }
    }
    return { username: id, nickname, avatarUrl };
  }

  async queryCategories(credential: Record<string, string>): Promise<JuejinCategory[]> {
    const root = await this.post("/tag_api/v1/query_category_list", credential, {});
    return this.data(root).flatMap((item) => {
      const category = record(item.category);
      if (!category) return [];
      const categoryId = exactId(category.category_id);
      const categoryName = name(category.category_name);
      const rankValue = typeof category.rank === "number" ? category.rank : Number(category.rank ?? 0);
      const rank = Number.isSafeInteger(rankValue) ? rankValue : 0;
      const hotTags = Array.isArray(item.hot_tags) ? item.hot_tags.map((rawTag) => {
        const tag = record(rawTag);
        if (!tag) throw new ProviderError(false, "掘金分类标签格式无效");
        return { id: exactId(tag.tag_id), name: name(tag.tag_name) };
      }) : [];
      return [{ id: categoryId, name: categoryName, rank, hotTags }];
    });
  }

  async queryTags(credential: Record<string, string>, cursor: string, limit: number): Promise<JuejinTagPage> {
    const root = await this.post("/tag_api/v1/query_tag_list", credential, {
      cursor,
      key_word: "",
      limit,
      sort_type: 1,
    });
    const tags = this.data(root).map((item) => {
      const tag = record(item.tag);
      if (!tag) throw new ProviderError(false, "掘金标签格式无效");
      return { id: exactId(item.tag_id), name: name(tag.tag_name) };
    });
    const nextCursor = root.cursor === undefined || root.cursor === null ? cursor : String(root.cursor);
    return { tags, hasMore: root.has_more === true, cursor: nextCursor };
  }

  async searchTags(keyword: string, limit: number): Promise<JuejinTagCandidate[]> {
    const endpoint = this.endpoint("/tag_api/v1/query_tag_list");
    endpoint.searchParams.set("key_word", keyword);
    endpoint.searchParams.set("cursor", "0");
    endpoint.searchParams.set("limit", String(Math.max(1, Math.min(limit, 20))));
    let raw: string;
    try {
      const response = await fetch(endpoint, {
        headers: { origin: "https://juejin.cn", referer: "https://juejin.cn/" },
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) throw new ProviderError(false);
      raw = await readProviderBody(response);
    } catch (error) {
      if (error instanceof ProviderError) throw error;
      throw new ProviderError(false);
    }
    const root = this.parseRoot(raw);
    return this.data(root).map((item) => {
      const tag = record(item.tag);
      return {
        id: exactId(item.id ?? item.tag_id),
        name: name(item.tag_name ?? tag?.tag_name),
      };
    });
  }

  async createDraft(credential: Record<string, string>, input: JuejinDraftInput): Promise<string> {
    const root = await this.post("/content_api/v1/article_draft/create", credential, {
      title: input.title,
      brief_content: input.briefContent,
      mark_content: input.markdownContent,
      category_id: input.categoryId,
      tag_ids: input.tagIds,
      link_url: "",
      cover_image: input.coverImage,
      edit_type: 10,
      html_content: "deprecated",
      theme_ids: [],
      pics: [],
    });
    const data = record(root.data);
    const id = data?.id;
    if (typeof id === "string" && id) return id;
    if (typeof id === "number" && Number.isSafeInteger(id) && id > 0) return String(id);
    throw new ProviderError(false, "掘金草稿创建响应无效");
  }

  async publishDraft(credential: Record<string, string>, draftId: string): Promise<string> {
    const root = await this.post("/content_api/v1/article/publish", credential, {
      draft_id: draftId,
      sync_to_org: false,
      column_ids: [],
      theme_ids: [],
    });
    const data = record(root.data);
    const id = data?.article_id ?? data?.id;
    if (typeof id === "string" && id) return id;
    if (typeof id === "number" && Number.isSafeInteger(id) && id > 0) return String(id);
    throw new ProviderError(false, "掘金发布响应无效");
  }

  private async post(path: string, credential: Record<string, string>, body: unknown): Promise<Record<string, unknown>> {
    const fields = credential as Credentials;
    if (!fields.cookie || /[\r\n]/.test(fields.cookie)
      || (fields.csrfToken !== undefined && /[\r\n]/.test(fields.csrfToken))) {
      throw new ProviderError(true, "掘金授权已失效");
    }
    const endpoint = this.endpoint(path);
    endpoint.searchParams.set("aid", fields.aid?.trim() || this.env.JUEJIN_DEFAULT_AID?.trim() || "2608");
    endpoint.searchParams.set("uuid", fields.uuid?.trim() || "");
    let response: Response;
    let raw: string;
    try {
      response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          cookie: fields.cookie,
          origin: "https://juejin.cn",
          referer: "https://juejin.cn/",
          ...(fields.csrfToken ? { "x-secsdk-csrf-token": fields.csrfToken } : {}),
        },
        body: JSON.stringify(body),
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) throw new ProviderError(response.status === 401 || response.status === 403);
      raw = await readProviderBody(response);
    } catch (error) {
      if (error instanceof ProviderError) throw error;
      throw new ProviderError(false);
    }
    if (new TextEncoder().encode(raw).byteLength > MAX_RESPONSE_BYTES) throw new ProviderError(false);
    try {
      // Juejin IDs can exceed Number.MAX_SAFE_INTEGER; quote long JSON integers before parsing.
      return this.parseRoot(raw);
    } catch (error) {
      if (error instanceof ProviderError) throw error;
      throw new ProviderError(false);
    }
  }

  private data(root: Record<string, unknown>): Array<Record<string, unknown>> {
    if (!Array.isArray(root.data)) throw new ProviderError(false, "掘金目录响应格式无效");
    return root.data.map((item) => {
      const value = record(item);
      if (!value) throw new ProviderError(false, "掘金目录响应格式无效");
      return value;
    });
  }

  private parseRoot(raw: string): Record<string, unknown> {
    if (new TextEncoder().encode(raw).byteLength > MAX_RESPONSE_BYTES) throw new ProviderError(false);
    try {
      // Keep all provider IDs that may exceed Number.MAX_SAFE_INTEGER as decimal strings.
      const lossless = raw.replace(/("(?:category_id|tag_id|article_id|draft_id|user_id|id)"\s*:\s*)(\d{16,})(?=\s*[,}])/g, "$1\"$2\"");
      const root = record(JSON.parse(lossless));
      if (!root) throw new Error("invalid JSON");
      const errorNo = typeof root.err_no === "number" ? root.err_no : Number(root.err_no ?? 0);
      if (errorNo !== 0) {
        const message = typeof root.err_msg === "string" ? root.err_msg.toLowerCase() : "";
        const expired = errorNo === 401 || message.includes("请登录")
          || message.includes("登录失效") || message.includes("unauthorized");
        throw new ProviderError(expired, expired ? "掘金授权已失效" : "掘金接口暂不可用");
      }
      return root;
    } catch (error) {
      if (error instanceof ProviderError) throw error;
      throw new ProviderError(false);
    }
  }

  private endpoint(path: string): URL {
    let base: URL;
    try {
      base = new URL(this.env.JUEJIN_API_BASE_URL || DEFAULT_BASE_URL);
    } catch {
      throw new ProviderError(false, "掘金接口地址无效");
    }
    if (base.protocol !== "https:" || base.hostname !== "api.juejin.cn"
      || base.username || base.password || base.search || base.hash) throw new ProviderError(false, "掘金接口地址无效");
    return new URL(path, base);
  }
}
