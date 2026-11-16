import type { PresentationAsset, PresentationDeck, PresentationRenderer } from "../interfaces/agent-presentation.js";
import { PresentationError } from "../services/agent-presentation-validation.js";

const MAX_RESPONSE_BYTES = 50 * 1024 * 1024;
const REVISION = "b243a950e9f85998705551fb1b77c6459573ff39";
function decode(value: unknown, max: number): Uint8Array {
  if (typeof value !== "string" || value.length > Math.ceil(max / 3) * 4 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) throw new Error("Invalid render asset");
  const raw = atob(value);
  if (!raw.length || raw.length > max) throw new Error("Invalid render size");
  return Uint8Array.from(raw, char => char.charCodeAt(0));
}
/** Only the trusted renderer endpoint receives slide content; redirects never receive its token. */
export class HttpPresentationRendererMapper implements PresentationRenderer {
  constructor(private readonly endpoint: string, private readonly token: string, private readonly transport = fetch.bind(globalThis)) {}

  async render(deck: PresentationDeck, assets: PresentationAsset[] = []) {
    try {
      const url = new URL(this.endpoint);
      if (url.protocol !== "https:" && !(url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname))) throw new Error();
      if (url.username || url.password) throw new Error();
      const response = await this.transport(new Request(new URL("/v1/render", url), { method: "POST", redirect: "manual",
        signal: AbortSignal.timeout(240_000), headers: { authorization: `Bearer ${this.token}`, "content-type": "application/json" }, body: JSON.stringify({ ...deck, assets }) }));
      if (response.status === 422) {
        const detail = await response.json() as { detail?: unknown };
        if (typeof detail.detail === "string" && /^PPT_(TEXT_FIT|SVG_INVALID|ASSET_INVALID):/.test(detail.detail)) {
          throw new PresentationError(422, detail.detail.slice(0, 350));
        }
      }
      if (!response.ok || !response.body) throw new Error();
      const reader = response.body.getReader();
      const chunks: Uint8Array[] = [];
      let total = 0;
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          total += value.byteLength;
          if (total > MAX_RESPONSE_BYTES) { await reader.cancel(); throw new Error(); }
          chunks.push(value);
        }
      } finally { reader.releaseLock(); }
      const bytes = new Uint8Array(total);
      let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      const result = JSON.parse(new TextDecoder().decode(bytes));
      if (result.revision !== REVISION || result.quality?.passed !== true || !Array.isArray(result.previews)
        || result.previews.length !== deck.slides.length || !result.source) throw new Error();
      const pptx = decode(result.pptx, 20 * 1024 * 1024);
      if (pptx[0] !== 80 || pptx[1] !== 75) throw new Error();
      const previews = result.previews.map((value: unknown) => {
        const png = decode(value, 4 * 1024 * 1024);
        if (png[0] !== 137 || png[1] !== 80 || png[2] !== 78 || png[3] !== 71) throw new Error();
        return png;
      });
      const source = new TextEncoder().encode(JSON.stringify({ schema: "dinqor.presentation-source.v1", ...result.source,
        quality: { passed: true } }));
      if (source.length > 8 * 1024 * 1024) throw new Error();
      return { pptx, source, previews };
    } catch (error) {
      if (error instanceof PresentationError) throw error;
      throw new PresentationError(502, "PPT_RENDER_FAILED: 渲染器未能生成可用的演示文稿结果");
    }
  }
}
