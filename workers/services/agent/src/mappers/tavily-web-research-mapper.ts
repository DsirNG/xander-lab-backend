import {
  AgentWebResearchError,
  type AgentWebProviderSource,
  type AgentWebResearchPort,
  type AgentWebSearchInput,
} from "../interfaces/agent-web-research-port.js";

const MAX_RESPONSE_BYTES = 512_000;
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === "object" && !Array.isArray(value);
const text = (value: unknown): string => typeof value === "string" ? value : "";

/** Calls fixed Tavily endpoints with bounded, cancellable responses and sanitized failures. */
export class TavilyWebResearchMapper implements AgentWebResearchPort {
  constructor(private readonly apiKey: string, private readonly request: typeof fetch = fetch.bind(globalThis), private readonly timeoutMs = 15_000) {}

  async search(input: AgentWebSearchInput, signal: AbortSignal): Promise<AgentWebProviderSource[]> {
    const body = await this.post("search", {
      query: input.query, max_results: input.limit, search_depth: "basic",
      include_answer: false, include_raw_content: false,
      ...(input.timeRange ? { time_range: input.timeRange } : {}),
      ...(input.domains?.length ? { include_domains: input.domains } : {}),
    }, signal);
    if (!Array.isArray(body.results)) throw new AgentWebResearchError("WEB_INVALID_RESPONSE", "搜索服务返回了无效结果");
    return body.results.filter(record).slice(0, input.limit).map(item => ({
      title: text(item.title), url: text(item.url), snippet: text(item.content),
      publishedAt: text(item.published_date),
    }));
  }

  async read(url: string, signal: AbortSignal): Promise<AgentWebProviderSource> {
    const body = await this.post("extract", { urls: [url], extract_depth: "basic", format: "markdown", include_images: false }, signal);
    const item = Array.isArray(body.results) ? body.results.find(record) : undefined;
    if (!item || !text(item.raw_content).trim()) throw new AgentWebResearchError("WEB_PAGE_UNAVAILABLE", "无法读取此网页，可能需要登录或网站限制了访问");
    return { url: text(item.url) || url, title: text(item.title), content: text(item.raw_content) };
  }

  async images(input: AgentWebSearchInput, signal: AbortSignal) {
    const body = await this.post("search", { query: input.query, max_results: input.limit,
      search_depth: "basic", include_images: true, include_image_descriptions: true,
      include_answer: false, ...(input.domains?.length ? { include_domains: input.domains } : {}) }, signal);
    const candidates: {url:string;description?:string;sourceUrl?:string}[] = [];
    // Source-linked page images take precedence; retain compatibility with top-level image search results.
    for (const page of Array.isArray(body.results) ? body.results.filter(record) : []) {
      for (const image of Array.isArray(page.images) ? page.images : []) {
        const item: Record<string, unknown> = record(image) ? image : {url:image};
        if (text(item.url)) candidates.push({url:text(item.url),description:text(item.description),sourceUrl:text(page.url)});
      }
    }
    for (const image of Array.isArray(body.images) ? body.images : []) {
      const item: Record<string, unknown> = record(image) ? image : {url:image};
      if (text(item.url)) candidates.push({url:text(item.url),description:text(item.description),sourceUrl:text(item.source_url) || undefined});
    }
    return [...new Map(candidates.map(item=>[item.url,item])).values()].slice(0,input.limit);
  }

  private async post(path: "search" | "extract", body: unknown, signal: AbortSignal): Promise<Record<string, unknown>> {
    signal.throwIfAborted();
    const controller = new AbortController();
    const abort = () => controller.abort(signal.reason);
    signal.addEventListener("abort", abort, { once: true });
    const timer = setTimeout(() => controller.abort("timeout"), this.timeoutMs);
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    try {
      const response = await this.request(`https://api.tavily.com/${path}`, {
        method: "POST", headers: { authorization: `Bearer ${this.apiKey}`, "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify(body), redirect: "manual", signal: controller.signal,
      });
      if (!response.ok) {
        await response.body?.cancel();
        throw new AgentWebResearchError(response.status === 429 ? "WEB_RATE_LIMITED" : "WEB_PROVIDER_UNAVAILABLE",
          response.status === 429 ? "搜索服务请求过于频繁，请稍后重试" : "联网资料服务暂时不可用", response.status === 429 || response.status >= 500);
      }
      if (!response.headers.get("content-type")?.toLowerCase().includes("application/json") || !response.body) {
        await response.body?.cancel();
        throw new AgentWebResearchError("WEB_INVALID_RESPONSE", "搜索服务返回了无效结果");
      }
      reader = response.body.getReader();
      const decoder = new TextDecoder();
      let bytes = 0;
      let content = "";
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        bytes += chunk.value.byteLength;
        if (bytes > MAX_RESPONSE_BYTES) throw new AgentWebResearchError("WEB_RESPONSE_TOO_LARGE", "网页资料过大，请缩小查询范围");
        content += decoder.decode(chunk.value, { stream: true });
      }
      const parsed: unknown = JSON.parse(content + decoder.decode());
      if (!record(parsed)) throw new AgentWebResearchError("WEB_INVALID_RESPONSE", "搜索服务返回了无效结果");
      return parsed;
    } catch (error) {
      signal.throwIfAborted();
      if (error instanceof AgentWebResearchError) throw error;
      if (controller.signal.aborted) throw new AgentWebResearchError("WEB_TIMEOUT", "联网资料服务超时，请稍后重试", true);
      throw new AgentWebResearchError("WEB_PROVIDER_UNAVAILABLE", "联网资料服务暂时不可用", true);
    } finally {
      clearTimeout(timer);
      signal.removeEventListener("abort", abort);
      await reader?.cancel().catch(() => {});
      reader?.releaseLock();
    }
  }
}
