import type { PresentationMaterialCandidate, PresentationMaterialPort } from "../interfaces/presentation-material-port.js";
import { publicWebUrl } from "../services/agent-web-research-service.js";
import { PresentationError } from "../services/agent-presentation-validation.js";

async function bounded(response: Response, maximum: number) {
  if (!response.ok || !response.body) throw new PresentationError(502, "PPT 素材下载失败，请选择其他来源");
  const reader = response.body.getReader(), chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read(); if (done) break;
      length += value.byteLength;
      if (length > maximum) { await reader.cancel(); throw new PresentationError(413, "PPT 素材文件过大"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const out = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { out.set(chunk, offset); offset += chunk.length; }
  return out;
}

/** Downloads only a search-session candidate, validates each redirect and normalizes bytes in the isolated renderer. */
export class HttpPresentationMaterialMapper implements PresentationMaterialPort {
  constructor(private readonly endpoint: string, private readonly token: string, private readonly transport: typeof fetch = fetch.bind(globalThis)) {}
  async read(candidate: PresentationMaterialCandidate, signal: AbortSignal) {
    try {
    const abort = AbortSignal.any([signal, AbortSignal.timeout(45_000)]);
    let url = publicWebUrl(candidate.url), response: Response | undefined;
    for (let hop = 0; hop <= 3; hop++) {
      response = await this.transport(url, { redirect: "manual", signal: abort, headers: { accept: "image/png,image/jpeg,image/webp,image/gif,image/svg+xml" } });
      if (![301,302,303,307,308].includes(response.status)) break;
      const location = response.headers.get("location"); await response.body?.cancel();
      if (!location || hop === 3) throw new PresentationError(502, "PPT 素材重定向无效");
      url = publicWebUrl(new URL(location, url).href);
    }
    const type = response!.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase();
    if (!type || !["image/png","image/jpeg","image/webp","image/gif","image/svg+xml"].includes(type)) {
      await response!.body?.cancel(); throw new PresentationError(400, "PPT 素材不是受支持的图片");
    }
    const bytes = await bounded(response!, 8 * 1024 * 1024);
    const binary = []; for (let offset = 0; offset < bytes.length; offset += 8192) binary.push(String.fromCharCode(...bytes.subarray(offset, offset + 8192)));
    const endpoint = new URL("/v1/normalize-image", this.endpoint);
    if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password) throw new PresentationError(503, "PPT 渲染服务尚未配置");
    // workerd supports only manual/follow. Reject redirects through bounded's status check.
    const result = await this.transport(endpoint, { method: "POST", redirect: "manual", signal: abort,
      headers: { authorization: `Bearer ${this.token}`, "content-type": "application/json" }, body: JSON.stringify({ contentType: type, data: btoa(binary.join("")) }) });
    const decoded = JSON.parse(new TextDecoder().decode(await bounded(result, 12 * 1024 * 1024))) as { data: string; width: number; height: number };
    if (!Number.isSafeInteger(decoded.width) || !Number.isSafeInteger(decoded.height) || decoded.width < 1 || decoded.height < 1 || decoded.width * decoded.height > 16_000_000
      || typeof decoded.data !== "string" || decoded.data.length > 12 * 1024 * 1024) throw new PresentationError(502, "PPT 素材转换失败");
    const raw = atob(decoded.data), png = Uint8Array.from(raw, c=>c.charCodeAt(0));
    if (png.length > 8 * 1024 * 1024 || png[0] !== 137 || png[1] !== 80 || png[2] !== 78 || png[3] !== 71) throw new PresentationError(502, "PPT 素材转换失败");
    return { bytes: png, contentType: "image/png" as const, width: decoded.width, height: decoded.height };
    } catch (error) {
      signal.throwIfAborted();
      if (error instanceof PresentationError) throw error;
      throw new PresentationError(502, "PPT 素材转换失败");
    }
  }
}
