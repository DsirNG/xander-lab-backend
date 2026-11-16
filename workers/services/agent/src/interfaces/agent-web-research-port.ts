/** Provider-independent public-web evidence; snippets are not fetched page bodies. */
export interface AgentWebSource {
  sourceId: string;
  title: string;
  url: string;
  snippet: string;
  publishedAt: string | null;
  retrievedAt: string;
  content?: string;
  truncated?: boolean;
}

export interface AgentWebSearchInput {
  query: string;
  limit: number;
  timeRange?: "day" | "week" | "month" | "year";
  domains?: string[];
}

export interface AgentWebProviderSource {
  title?: string;
  url: string;
  snippet?: string;
  publishedAt?: string;
  content?: string;
}

/** Remote search/extraction adapter. The Worker never fetches model-supplied URLs. */
export interface AgentWebResearchPort {
  images?(input: AgentWebSearchInput, signal: AbortSignal): Promise<{ url: string; description?: string; sourceUrl?: string }[]>;
  search(input: AgentWebSearchInput, signal: AbortSignal): Promise<AgentWebProviderSource[]>;
  read(url: string, signal: AbortSignal): Promise<AgentWebProviderSource>;
}

export interface AgentWebResearchEnvironment {
  AGENT_WEB_SEARCH_ENABLED?: string;
  TAVILY_API_KEY?: string;
}

export class AgentWebResearchError extends Error {
  constructor(readonly code: string, message: string, readonly retryable = false) {
    super(message);
    this.name = "AgentWebResearchError";
  }
}
