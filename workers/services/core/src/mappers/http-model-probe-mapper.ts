import type { ModelProbeError, ModelProbeInput, ModelProbePort, ModelProbeResult } from "../interfaces/model-probe.js";

const MAX_RESPONSE_BYTES = 20 * 1024 * 1024;
const MAX_ERROR_BYTES = 64 * 1024;
const record = (value: unknown): Record<string, unknown> => value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};

/** Extract only useful diagnostic fields, redact credentials, and never return headers or stacks. */
function safeText(value: unknown, apiKey: string, maxLength = 2000): string | undefined {
  if (typeof value !== "string" && typeof value !== "number") return undefined;
  let text = String(value);
  for (const secret of [apiKey, encodeURIComponent(apiKey)]) {
    if (secret) text = text.split(secret).join("[REDACTED]");
  }
  return text.replace(/Bearer\s+[^\s"',;<>]+/gi, "Bearer [REDACTED]")
    .replace(/\bsk-[A-Za-z0-9_-]{8,}/g, "[REDACTED]")
    .replace(/((?:api[_-]?key|access[_-]?token|authorization|password|secret)\s*[=:]\s*)[^\s&,;"'<>]+/gi, "$1[REDACTED]")
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "").trim().slice(0, maxLength) || undefined;
}

async function readBody(response: Response, limit: number): Promise<{ text: string; exceeded: boolean }> {
  if (!response.body) return { text: "", exceeded: false };
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) { await reader.cancel(); return { text: "", exceeded: true }; }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return { text: new TextDecoder().decode(bytes), exceeded: false };
}

function vendorError(body: Record<string, unknown>, response: Response, input: ModelProbeInput, plainText?: string): ModelProbeError | undefined {
  const error = record(body.error);
  const firstError = record(Array.isArray(body.errors) ? body.errors[0] : undefined);
  const message = safeText(error.message ?? firstError.message ?? body.message ?? body.msg ?? body.detail
    ?? (typeof body.error === "string" ? body.error : plainText), input.apiKey);
  const code = safeText(error.code ?? firstError.code ?? body.code, input.apiKey, 120);
  const type = safeText(error.type ?? body.type, input.apiKey, 120);
  const param = safeText(error.param ?? body.param, input.apiKey, 160);
  const requestId = safeText(response.headers.get("x-request-id") ?? response.headers.get("request-id") ?? body.request_id, input.apiKey, 160);
  if (!message && !code && !requestId) return undefined;
  return { message: message ?? "", ...(code ? { code } : {}), ...(type ? { type } : {}), ...(param ? { param } : {}), ...(requestId ? { requestId } : {}) };
}

function safeImage(value: unknown): string | undefined {
  if (typeof value !== "string" || value.length > MAX_RESPONSE_BYTES) return undefined;
  if (/^data:image\/(png|jpeg|webp|gif);base64,[a-z0-9+/]+={0,2}$/i.test(value)) return value;
  try {
    const url = new URL(value);
    if (url.protocol === "https:" && !url.username && !url.password && url.hostname.includes(".")
      && !/^[\d.]+$/.test(url.hostname) && !url.hostname.endsWith(".local") && !url.hostname.endsWith(".internal")) return url.href;
  } catch { /* Provider image values must be valid HTTPS URLs or bounded raster data. */ }
  return undefined;
}

function imageOutput(body: Record<string, unknown>, message: Record<string, unknown>): string[] {
  const images: string[] = [];
  const add = (value: unknown) => { const image = safeImage(value); if (image && !images.includes(image) && images.length < 4) images.push(image); };
  for (const raw of [...(Array.isArray(body.data) ? body.data : []), ...(Array.isArray(message.images) ? message.images : []), ...(Array.isArray(message.content) ? message.content : [])]) {
    const item = record(raw);
    if (typeof item.b64_json === "string") add(`data:image/png;base64,${item.b64_json}`);
    add(item.url ?? record(item.image_url).url ?? item.image_url);
  }
  if (typeof message.content === "string") {
    add(message.content);
    for (const match of message.content.matchAll(/!\[[^\]]*\]\((https:\/\/[^\s)]+|data:image\/[^\s)]+)\)/g)) add(match[1]);
  }
  return images;
}

function textOutput(body: Record<string, unknown>, choice: Record<string, unknown>, message: Record<string, unknown>): string {
  const content = message.content;
  const fromParts = Array.isArray(content)
    ? content.map((part) => {
      const item = record(part);
      return typeof item.text === "string" ? item.text : typeof item.content === "string" ? item.content : "";
    }).join("\n")
    : typeof content === "string" ? content : "";
  if (fromParts.trim()) return fromParts;
  for (const value of [message.reasoning_content, choice.text, body.output_text, body.response, body.content, body.text]) {
    if (typeof value === "string" && value.trim()) return value;
  }
  const nested = record(body.data);
  const nestedChoice = record(Array.isArray(nested.choices) ? nested.choices[0] : undefined);
  const nestedMessage = record(nestedChoice.message);
  return typeof nestedMessage.content === "string" ? nestedMessage.content : typeof nestedChoice.text === "string" ? nestedChoice.text : "";
}

/** Makes only explicit administrator requests. Returns bounded output and redacted provider diagnostics. */
export class HttpModelProbeMapper implements ModelProbePort {
  constructor(private readonly fetcher: typeof fetch = fetch.bind(globalThis)) {}

  async probe(input: ModelProbeInput): Promise<ModelProbeResult> {
    const started = Date.now();
    let path = "/models";
    const result = (ok: boolean, reason: string, response?: Response, extra: Partial<ModelProbeResult> = {}): ModelProbeResult =>
      ({ ok, reason, latencyMs: Date.now() - started, endpoint: path, ...(response ? { upstreamStatus: response.status } : {}), ...extra });
    let base: URL;
    try { base = new URL(input.baseUrl); } catch { return result(false, "invalid_url"); }
    const host = base.hostname.toLowerCase();
    if (base.protocol !== "https:" || base.username || base.password || base.search || base.hash
      || !host.includes(".") || host.endsWith(".local") || host.endsWith(".localhost")
      || host.endsWith(".internal") || host.includes(":") || /^[\d.]+$/.test(host)) return result(false, "invalid_url");
    const image = input.featureKey === "blog_agent_image";
    const vision = input.featureKey === "img2three_vision";
    const style = input.apiStyle;
    if (input.model && !input.prompt?.trim()) return result(false, "prompt_required");
    if (style === "DASHSCOPE_ASR") return result(false, "audio_required");
    if (vision && !input.imageDataUrl) return result(false, "image_required");
    let payload: Record<string, unknown> | undefined;
    if (input.model) {
      const prompt = input.prompt!.trim();
      const previous = (input.messages ?? []).map(({ role, content }) => ({ role, content }));
      const dimensions = input.imageSize ? { size: input.imageSize } : {};
      if (style === "IMAGES_GENERATIONS" || style === "CHAT_PROMPT") {
        path = style === "IMAGES_GENERATIONS" ? "/images/generations" : "/chat/completions";
        payload = { model: input.model, prompt, n: 1, ...dimensions };
      } else if (style === "RESPONSES") {
        path = "/responses";
        payload = { model: input.model, stream: false, input: [...previous, { role: "user", content: vision
          ? [{ type: "input_text", text: prompt }, { type: "input_image", image_url: input.imageDataUrl }]
          : prompt }], ...(input.maxOutputTokens ? { max_output_tokens: input.maxOutputTokens } : {}) };
      } else {
        path = "/chat/completions";
        payload = { model: input.model, stream: false, messages: [...previous, { role: "user", content: vision
          ? [{ type: "text", text: prompt }, { type: "image_url", image_url: { url: input.imageDataUrl } }] : prompt }],
          ...(image ? { modalities: ["image"], n: 1, ...dimensions } : input.maxOutputTokens ? { max_completion_tokens: input.maxOutputTokens } : {}) };
      }
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), image ? 90_000 : 60_000);
    try {
      const response = await this.fetcher(input.baseUrl.replace(/\/+$/, "") + path, {
        method: payload ? "POST" : "GET", redirect: "manual", signal: controller.signal,
        headers: { authorization: `Bearer ${input.apiKey}`, "content-type": "application/json" },
        ...(payload ? { body: JSON.stringify(payload) } : {}),
      });
      const read = await readBody(response, response.ok ? MAX_RESPONSE_BYTES : MAX_ERROR_BYTES);
      let body: Record<string, unknown> = {};
      let validJson = false;
      try { body = record(JSON.parse(read.text)); validJson = true; } catch { /* Non-JSON provider errors are handled below. */ }
      const diagnostic = vendorError(body, response, input, !validJson && response.headers.get("content-type")?.includes("text/plain") && !/<[a-z!]/i.test(read.text) ? read.text.split("\n")[0] : undefined);
      if (!response.ok || body.error) {
        const reason = response.status === 401 || response.status === 403 ? "authentication"
          : response.status === 429 ? "rate_limited" : response.status === 404 ? "not_found" : "upstream_error";
        return result(false, reason, response, diagnostic ? { error: diagnostic } : {});
      }
      if (read.exceeded) return result(false, "response_too_large", response);
      const rawResponse = safeText(read.text, input.apiKey, 20_000);
      if (!validJson) return result(false, "invalid_response", response, rawResponse ? { rawResponse } : {});
      if (!input.model) {
        if (!Array.isArray(body.data)) return result(false, "invalid_response", response, diagnostic ? { error: diagnostic } : {});
        const models = body.data.map((item) => safeText(record(item).id, input.apiKey, 100)).filter((id): id is string => Boolean(id)).slice(0, 500);
        return result(true, "success", response, { models: [...new Set(models)] });
      }
      const choice = record(Array.isArray(body.choices) ? body.choices[0] : undefined);
      const message = record(choice.message);
      const images = imageOutput(body, message);
      let text = textOutput(body, choice, message);
      if (style === "RESPONSES") {
        text = typeof body.output_text === "string" ? body.output_text : (Array.isArray(body.output) ? body.output : [])
          .flatMap((item) => Array.isArray(record(item).content) ? record(item).content as unknown[] : [])
          .map((item) => typeof record(item).text === "string" ? record(item).text as string : "").join("\n");
      }
      const finishReason = safeText(choice.finish_reason ?? body.status, input.apiKey, 80);
      if (image && !images.length || !image && (!text.trim() || body.status === "failed" || body.status === "cancelled")) {
        return result(false, "invalid_response", response, { ...(diagnostic ? { error: diagnostic } : {}), ...(rawResponse ? { rawResponse } : {}), output: { ...(finishReason ? { finishReason } : {}) } });
      }
      // Inline base64 belongs in the image preview, never in a text bubble or history.
      if (image) text = text.replace(/data:image\/[^;\s]+;base64,[a-z0-9+/=]+/gi, "");
      const truncated = text.length > 24_000 || body.status === "incomplete" || choice.finish_reason === "length";
      return result(true, "success", response, { ...(rawResponse ? { rawResponse } : {}), output: { ...(text.trim() ? { text: safeText(text, input.apiKey, 24_000) } : {}), ...(images.length ? { images } : {}), ...(finishReason ? { finishReason } : {}), ...(truncated ? { truncated } : {}) } });
    } catch {
      return result(false, controller.signal.aborted ? "timeout" : "connection");
    } finally { clearTimeout(timer); }
  }
}
