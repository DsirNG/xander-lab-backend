import type {
  BlogAgentModelCandidate,
  BlogAgentModelCandidatePort,
  BlogAgentModelInput,
  BlogAgentModelPort,
  BlogAgentModelResponse,
} from "../interfaces/blog-agent-model.js";

const TRANSIENT_STATUSES = new Set([429, 500, 502, 503, 504]);
const MAX_RESPONSE_BYTES = 1_000_000;

class ModelHttpError extends Error {
  constructor(readonly status: number) {
    super(`Blog model returned HTTP ${status}`);
  }
}

function retryable(error: unknown): boolean {
  return !(error instanceof ModelHttpError) || TRANSIENT_STATUSES.has(error.status);
}

function backoff(attempt: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 500 * attempt));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function nonNegativeInteger(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

function baseUrl(value: string): string {
  const url = new URL(value);
  if (url.protocol !== "https:" && url.hostname !== "localhost" && url.hostname !== "127.0.0.1") {
    throw new Error("Blog model endpoint must use HTTPS");
  }
  return value.replace(/\/+$/, "");
}

function chatPayload(candidate: BlogAgentModelCandidate, input: BlogAgentModelInput) {
  return {
    model: candidate.model,
    stream: false,
    response_format: { type: "json_object" },
    messages: [
      { role: "system", content: input.instruction },
      { role: "user", content: input.input },
    ],
  };
}

function responsesPayload(candidate: BlogAgentModelCandidate, input: BlogAgentModelInput) {
  return {
    model: candidate.model,
    store: false,
    instructions: input.instruction,
    input: input.input,
    ...(input.webSearch ? { tools: [{ type: "web_search" }] } : {}),
  };
}

function outputText(body: Record<string, unknown>, chat: boolean): string {
  if (chat) {
    const choices = body.choices;
    const first = Array.isArray(choices) && isRecord(choices[0]) ? choices[0] : null;
    const message = first && isRecord(first.message) ? first.message : null;
    const content = message?.content;
    if (typeof content === "string") return content;
    if (Array.isArray(content)) {
      return content.filter(isRecord).map((part) => typeof part.text === "string" ? part.text : "").join("");
    }
    return "";
  }
  if (typeof body.output_text === "string") return body.output_text;
  const output = Array.isArray(body.output) ? body.output : [];
  return output.flatMap((item) => isRecord(item) && Array.isArray(item.content) ? item.content : [])
    .filter(isRecord)
    .map((part) => typeof part.text === "string" ? part.text : "")
    .join("");
}

function usage(body: Record<string, unknown>, chat: boolean) {
  const raw = isRecord(body.usage) ? body.usage : {};
  const details = isRecord(chat ? raw.prompt_tokens_details : raw.input_tokens_details)
    ? (chat ? raw.prompt_tokens_details : raw.input_tokens_details) as Record<string, unknown>
    : {};
  return {
    inputTokens: nonNegativeInteger(chat ? raw.prompt_tokens : raw.input_tokens),
    cachedTokens: nonNegativeInteger(details.cached_tokens),
    outputTokens: nonNegativeInteger(chat ? raw.completion_tokens : raw.output_tokens),
  };
}

function jsonPayload(text: string): string {
  const trimmed = text.trim();
  const withoutFence = trimmed.startsWith("```")
    ? trimmed.slice(trimmed.indexOf("\n") + 1, trimmed.lastIndexOf("```"))
    : trimmed;
  const value: unknown = JSON.parse(withoutFence);
  if (!isRecord(value)) throw new Error("Blog model returned invalid JSON");
  return JSON.stringify(value);
}

export class CoreBlogAgentModelMapper implements BlogAgentModelPort {
  constructor(private readonly gateway: BlogAgentModelCandidatePort) {}

  async complete(input: BlogAgentModelInput): Promise<BlogAgentModelResponse> {
    const candidates = await this.gateway.candidates();
    if (candidates.length === 0) throw new Error("Blog model is not configured");
    let failure: unknown;
    for (const candidate of candidates) {
      try {
        return await this.request(candidate, input);
      } catch (error) {
        failure = error;
      }
    }
    throw failure instanceof Error ? failure : new Error("Blog model request failed");
  }

  private async request(candidate: BlogAgentModelCandidate, input: BlogAgentModelInput): Promise<BlogAgentModelResponse> {
    const chat = candidate.apiStyle === "CHAT_COMPLETIONS";
    if (!chat && candidate.apiStyle !== "RESPONSES") throw new Error("Unsupported blog model API style");
    const endpoint = `${baseUrl(candidate.baseUrl)}${chat ? "/chat/completions" : "/responses"}`;
    let lastError: Error = new Error("Blog model request failed");
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        const response = await fetch(endpoint, {
          method: "POST",
          headers: { authorization: `Bearer ${candidate.apiKey}`, "content-type": "application/json" },
          body: JSON.stringify(chat ? chatPayload(candidate, input) : responsesPayload(candidate, input)),
          redirect: "manual",
          signal: AbortSignal.timeout(90_000),
        });
        if (!response.ok) {
          throw new ModelHttpError(response.status);
        }
        const raw = await response.text();
        if (new TextEncoder().encode(raw).byteLength > MAX_RESPONSE_BYTES) throw new Error("Blog model response is too large");
        const body: unknown = JSON.parse(raw);
        if (!isRecord(body)) throw new Error("Blog model returned an invalid response");
        if (isRecord(body.error)) {
          throw new ModelHttpError(502);
        }
        const text = outputText(body, chat);
        if (!text.trim()) throw new Error("Blog model returned no content");
        return { payloadJson: jsonPayload(text), ...usage(body, chat), model: candidate.model };
      } catch (error) {
        lastError = error instanceof Error ? error : new Error("Blog model request failed");
        if (attempt < 2 && retryable(error) && !(error instanceof SyntaxError)) {
          await backoff(attempt + 1);
          continue;
        }
        throw lastError;
      }
    }
    throw lastError;
  }
}
