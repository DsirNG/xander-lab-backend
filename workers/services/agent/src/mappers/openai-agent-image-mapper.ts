import type { AgentGeneratedImage, AgentImageGenerationPort } from "../interfaces/agent-image-generation-port.js";
import type { AgentImageModelEndpoint } from "../interfaces/agent-image-model-gateway.js";

const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
const MAX_BASE64_LENGTH = Math.ceil(MAX_IMAGE_BYTES / 3) * 4;
const MAX_PROVIDER_RESPONSE_BYTES = 30_000_000;

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function safeUsage(value: unknown): AgentGeneratedImage["usage"] {
  const usage = record(value);
  const details = record(usage.input_tokens_details ?? usage.prompt_tokens_details);
  const inputTokens = Number(usage.input_tokens ?? usage.prompt_tokens ?? 0);
  const outputTokens = Number(usage.output_tokens ?? usage.completion_tokens ?? 0);
  const cachedTokens = Number(details.cached_tokens ?? 0);
  const valid = (number: number) => Number.isSafeInteger(number) && number >= 0;
  if (!valid(inputTokens) || !valid(outputTokens) || !valid(cachedTokens) || inputTokens + outputTokens === 0) {
    throw new Error("Image model did not return billable token usage");
  }
  return { inputTokens, outputTokens, cachedTokens };
}

function imageData(value: unknown): { bytes: Uint8Array; contentType: string } {
  let encoded: unknown;
  let contentType = "image/png";
  if (typeof value === "string") {
    const dataUrl = /^data:(image\/[a-z0-9.+-]+);base64,([a-z0-9+/=]+)$/i.exec(value);
    if (!dataUrl) throw new Error("Image model did not return base64 image data");
    contentType = dataUrl[1].toLowerCase();
    encoded = dataUrl[2];
  } else {
    const root = record(value);
    const data = Array.isArray(root.data) ? record(root.data[0]) : {};
    const choices = Array.isArray(root.choices) ? record(root.choices[0]) : {};
    const message = record(choices.message);
    const content = message.content;
    if (Array.isArray(content)) {
      for (const partValue of content) {
        const part = record(partValue);
        const imageUrl = record(part.image_url).url ?? part.url;
        if (typeof imageUrl === "string" && imageUrl.startsWith("data:")) return imageData(imageUrl);
        if (typeof part.b64_json === "string") { encoded = part.b64_json; break; }
      }
    }
    if (typeof encoded !== "string") encoded = data.b64_json;
    if (typeof encoded !== "string" && typeof content === "string" && content.startsWith("data:")) {
      return imageData(content);
    }
    if (typeof data.content_type === "string" && data.content_type.startsWith("image/")) {
      contentType = data.content_type.toLowerCase();
    }
  }
  if (typeof encoded !== "string" || encoded.length === 0 || encoded.length > MAX_BASE64_LENGTH
    || !/^[a-z0-9+/]+={0,2}$/i.test(encoded)) throw new Error("Image model returned invalid image data");
  const binary = atob(encoded);
  if (binary.length === 0 || binary.length > MAX_IMAGE_BYTES) throw new Error("Generated image exceeds the 20MB limit");
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return { bytes, contentType };
}

function decodedImage(value: unknown): { bytes: Uint8Array; contentType: string; usage: AgentGeneratedImage["usage"] } {
  const image = imageData(value);
  const usage = safeUsage(record(value).usage);
  return { bytes: image.bytes, contentType: image.contentType, usage };
}

function extension(contentType: string): string {
  if (contentType === "image/jpeg") return "jpg";
  if (contentType === "image/webp") return "webp";
  if (contentType === "image/gif") return "gif";
  if (contentType === "image/png") return "png";
  throw new Error("Image model returned an unsupported image type");
}

async function readProviderJson(response: Response): Promise<unknown> {
  const declared = response.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_PROVIDER_RESPONSE_BYTES)) {
    throw new Error("Image provider response is too large");
  }
  if (!response.body) throw new Error("Image provider returned an empty response");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_PROVIDER_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("Image provider response is too large");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
}

/** Calls the configured OpenAI-compatible image endpoint and accepts bounded inline image bytes. */
export class OpenAiAgentImageMapper implements AgentImageGenerationPort {
  constructor(private readonly fetcher: typeof fetch = fetch) {}

  async generate(endpoint: AgentImageModelEndpoint, prompt: string): Promise<AgentGeneratedImage> {
    const path = endpoint.apiStyle === "IMAGES_GENERATIONS" ? "/images/generations" : "/chat/completions";
    const payload = endpoint.apiStyle === "IMAGES_GENERATIONS"
      ? { model: endpoint.model, prompt, size: "1024x1024", n: 1, response_format: "b64_json" }
      : endpoint.apiStyle === "CHAT_COMPLETIONS"
        ? { model: endpoint.model, messages: [{ role: "user", content: prompt }], modalities: ["image"], size: "1024x1024", n: 1 }
        : { model: endpoint.model, prompt, size: "1024x1024", n: 1 };
    let response: Response;
    try {
      response = await this.fetcher(endpoint.baseUrl.replace(/\/+$/, "") + path, {
        method: "POST", headers: { authorization: `Bearer ${endpoint.apiKey}`, "content-type": "application/json" },
        body: JSON.stringify(payload), redirect: "manual", signal: AbortSignal.timeout(90_000),
      });
    } catch { throw new Error("Image provider request failed"); }
    if (!response.ok) throw new Error("Image provider returned an error");
    let value: unknown;
    try { value = await readProviderJson(response); } catch { throw new Error("Image provider returned invalid JSON or an oversized body"); }
    const parsed = decodedImage(value);
    const root = record(value);
    const usage = parsed.usage;
    const size = typeof record(Array.isArray(root.data) ? root.data[0] : null).size === "string"
      ? record(Array.isArray(root.data) ? root.data[0] : null).size as string : "1024x1024";
    return {
      bytes: parsed.bytes, contentType: parsed.contentType, extension: extension(parsed.contentType),
      model: endpoint.model, size, usage,
    };
  }
}
