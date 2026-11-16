import { AgentWebResearchError, type AgentWebResearchPort, type AgentWebSource, type AgentWebProviderSource, type AgentWebSearchInput } from "../interfaces/agent-web-research-port.js";

export { AgentWebResearchError };
const clean = (value: string, max: number) => value.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "").trim().slice(0, max);

/** Only public HTTP(S) hostnames are passed to the remote extractor, never fetched locally. */
export function publicWebUrl(value: unknown): string {
  if (typeof value !== "string" || value.length > 2000) throw new AgentWebResearchError("WEB_INVALID_URL", "请输入公开的 HTTP 或 HTTPS 网页地址");
  let url: URL;
  try { url = new URL(value); } catch { throw new AgentWebResearchError("WEB_INVALID_URL", "网页地址格式不正确"); }
  const host = url.hostname.toLowerCase().replace(/\.$/, "");
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password
    || (url.port && url.port !== "80" && url.port !== "443")
    || !host.includes(".") || host.includes(":") || /^\d+(\.\d+)*$/.test(host)
    || /(?:^|\.)(?:localhost|local|internal|intranet|test|invalid|example|onion|arpa)$/.test(host)
    || !/^[a-z0-9.-]+$/.test(host) || host.split(".").some(label => !label || label.startsWith("-") || label.endsWith("-"))) {
    throw new AgentWebResearchError("WEB_INVALID_URL", "仅支持公开网站，不能读取本机、内网、IP 地址或带凭据的网址");
  }
  url.hostname = host;
  url.hash = "";
  return url.toString();
}

/** Creates a separate budget/cache for each durable turn, shared with delegated research. */
export class AgentWebResearchService {
  private nativeSearch = true;
  constructor(private provider?: AgentWebResearchPort,
    private readonly resolve?: () => Promise<{ provider?: AgentWebResearchPort; allowNativeSearch: boolean }>) {}
  get available(): boolean { return !!this.provider; }
  get nativeSearchAllowed(): boolean { return !this.provider && this.nativeSearch; }
  async initialize(): Promise<void> {
    if (!this.resolve) return;
    try {
      const settings = await this.resolve();
      this.provider = settings.provider;
      this.nativeSearch = settings.allowNativeSearch;
    } catch {
      // Config/decryption failures disable research, not ordinary conversations.
      this.provider = undefined;
      this.nativeSearch = false;
    }
  }
  createSession(): AgentWebResearchSession | undefined {
    return this.provider ? new AgentWebResearchSession(this.provider) : undefined;
  }
}

export class AgentWebResearchSession {
  private imageSearches = 0;
  private readonly imageCandidates = new Map<string, { url: string; description: string; sourceUrl: string }>();
  private searches = 0;
  private reads = 0;
  private remainingContent = 48_000;
  private readonly sources = new Map<string, AgentWebSource>();
  private readonly searchCache = new Map<string, AgentWebSource[]>();
  private readonly readCache = new Map<string, AgentWebSource>();

  constructor(private readonly provider: AgentWebResearchPort) {}

  async searchImages(args: Record<string, unknown>, signal: AbortSignal) {
    const query = typeof args.query === "string" ? clean(args.query, 501) : "";
    if (!query || query.length > 500) throw new AgentWebResearchError("WEB_INVALID_ARGUMENTS", "搜索关键词需要 1 至 500 个字符");
    if (!this.provider.images) throw new AgentWebResearchError("WEB_PROVIDER_UNAVAILABLE", "图片搜索服务尚未配置");
    if (++this.imageSearches > 5) throw new AgentWebResearchError("WEB_SEARCH_BUDGET", "本轮图片搜索预算已用完");
    const domains = Array.isArray(args.domains) ? args.domains.slice(0, 5).map(value => new URL(publicWebUrl(`https://${value}`)).hostname) : undefined;
    const raw = await this.provider.images({ query, limit: 8, domains }, signal);
    const results = [];
    for (const item of raw) {
      try {
        const url = publicWebUrl(item.url);
        const sourceUrl = item.sourceUrl ? publicWebUrl(item.sourceUrl) : url;
        const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(url));
        const candidateId = `img_${Array.from(new Uint8Array(digest)).slice(0,12).map(v=>v.toString(16).padStart(2,"0")).join("")}`;
        const value = { url, sourceUrl, description: clean(item.description ?? "", 500) };
        this.imageCandidates.set(candidateId, value);
        results.push({ candidateId, ...value });
      } catch (error) { if (!(error instanceof AgentWebResearchError)) throw error; }
    }
    return { results, untrusted: true, attribution: "Search candidates are not proof of product identity or permission. Verify the source and exact product before use." };
  }

  imageCandidate(id: unknown) {
    const value = typeof id === "string" ? this.imageCandidates.get(id) : undefined;
    if (!value) throw new AgentWebResearchError("WEB_INVALID_ARGUMENTS", "请使用本轮图片搜索返回的素材标识");
    return value;
  }

  async search(args: Record<string, unknown>, signal: AbortSignal) {
    signal.throwIfAborted();
    const query = typeof args.query === "string" ? clean(args.query, 501) : "";
    if (!query || query.length > 500) throw new AgentWebResearchError("WEB_INVALID_ARGUMENTS", "搜索关键词需要 1 至 500 个字符");
    const limit = args.limit ?? 5;
    if (!Number.isInteger(limit) || Number(limit) < 1 || Number(limit) > 8) throw new AgentWebResearchError("WEB_INVALID_ARGUMENTS", "搜索结果数量需为 1 至 8");
    const timeRange = args.timeRange;
    if (timeRange !== undefined && !["day", "week", "month", "year"].includes(String(timeRange))) throw new AgentWebResearchError("WEB_INVALID_ARGUMENTS", "搜索时间范围不合法");
    if (args.domains !== undefined && (!Array.isArray(args.domains) || args.domains.length > 5)) throw new AgentWebResearchError("WEB_INVALID_ARGUMENTS", "最多指定 5 个网站域名");
    const domains = (args.domains as unknown[] | undefined)?.map(value => {
      if (typeof value !== "string" || !/^[a-z0-9.-]+$/i.test(value)) throw new AgentWebResearchError("WEB_INVALID_ARGUMENTS", "请提供不带路径的网站域名");
      return new URL(publicWebUrl(`https://${value}`)).hostname;
    }).sort();
    const input: AgentWebSearchInput = { query, limit: Number(limit), timeRange: timeRange as AgentWebSearchInput["timeRange"], domains };
    const key = JSON.stringify(input);
    let results = this.searchCache.get(key);
    if (!results) {
      if (this.searches >= 3) throw new AgentWebResearchError("WEB_SEARCH_BUDGET", "本轮搜索次数已用完，请基于已有资料回答并说明未确认事项");
      this.searches++;
      const raw = await this.provider.search(input, signal);
      results = [];
      for (const item of raw.slice(0, input.limit)) {
        try {
          const source = await this.normalize(item);
          if (!results.some(existing => existing.url === source.url)) results.push(source);
        } catch (error) { if (!(error instanceof AgentWebResearchError)) throw error; }
      }
      signal.throwIfAborted();
      this.searchCache.set(key, results);
    }
    return { query, results, evidenceType: "search_snippets", untrusted: true };
  }

  async read(args: Record<string, unknown>, signal: AbortSignal) {
    signal.throwIfAborted();
    const url = publicWebUrl(args.url);
    const maxChars = args.maxChars ?? 12_000;
    if (!Number.isInteger(maxChars) || Number(maxChars) < 1000 || Number(maxChars) > 12_000) throw new AgentWebResearchError("WEB_INVALID_ARGUMENTS", "正文长度需为 1000 至 12000 个字符");
    const key = `${url}:${maxChars}`;
    let source = this.readCache.get(key);
    if (!source) {
      if (this.reads >= 5 || this.remainingContent <= 0) throw new AgentWebResearchError("WEB_READ_BUDGET", "本轮网页读取预算已用完，请基于已有资料回答");
      this.reads++;
      const raw = await this.provider.read(url, signal);
      // Validate redirected/extracted URLs too; the provider is the only network reader.
      source = await this.normalize(raw);
      const content = clean(raw.content ?? "", Math.min(Number(maxChars), this.remainingContent) + 1);
      if (!content) throw new AgentWebResearchError("WEB_PAGE_UNAVAILABLE", "网页没有可读取的正文");
      const cap = Math.min(Number(maxChars), this.remainingContent);
      source = { ...source, content: content.slice(0, cap), truncated: content.length > cap };
      signal.throwIfAborted();
      this.remainingContent -= source.content!.length;
      this.readCache.set(key, source);
    }
    return { url, results: [source], evidenceType: "page_content", untrusted: true };
  }

  private async normalize(raw: AgentWebProviderSource): Promise<AgentWebSource> {
    const url = publicWebUrl(raw.url);
    const existing = this.sources.get(url);
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(url));
    const sourceId = `src_${Array.from(new Uint8Array(digest)).slice(0, 8).map(byte => byte.toString(16).padStart(2, "0")).join("")}`;
    const date = raw.publishedAt && /^\d{4}-\d{2}-\d{2}(?:T|$)/.test(raw.publishedAt) ? Date.parse(raw.publishedAt) : NaN;
    const source: AgentWebSource = {
      sourceId, title: clean(raw.title ?? "", 300) || existing?.title || new URL(url).hostname,
      url, snippet: clean(raw.snippet ?? "", 1200) || existing?.snippet || "",
      publishedAt: Number.isFinite(date) ? new Date(date).toISOString() : existing?.publishedAt ?? null,
      retrievedAt: new Date().toISOString(),
    };
    this.sources.set(url, source);
    return source;
  }
}
