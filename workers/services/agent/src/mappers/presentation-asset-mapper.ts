import type { AgentImageMediaPort } from "../interfaces/agent-image-media-port.js";
import type { PresentationAsset, PresentationAssetPort, PresentationAttachmentPort, PresentationDeck } from "../interfaces/agent-presentation.js";
import { PresentationError } from "../services/agent-presentation-validation.js";

const MAX_IMAGE = 8 * 1024 * 1024;
const MAX_TOTAL = 20 * 1024 * 1024;
/** Resolve ownership before accessing signed storage URLs. The renderer never accesses the network. */
export class PresentationAssetMapper implements PresentationAssetPort {
  constructor(private readonly attachments: PresentationAttachmentPort, private readonly media: Pick<AgentImageMediaPort, "findOwned">,
    private readonly transport = fetch.bind(globalThis)) {}

  async load(userId: number, deck: PresentationDeck): Promise<PresentationAsset[]> {
    const wanted = new Map<string, { id: number; media: boolean }>();
    for (const slide of deck.slides) for (const element of slide.elements ?? []) if (element.type === "image") {
      const media = element.assetId !== undefined, id = (media ? element.assetId : element.attachmentId)!;
      wanted.set(`${media ? "media" : "attachment"}:${id}`, { id, media });
    }
    const assets: PresentationAsset[] = [];
    let total = 0;
    for (const [key, item] of wanted) {
      const ref = item.media ? await this.media.findOwned(userId, item.id) : (await this.attachments.resolveOwned(userId, [item.id]))[0];
      if (!ref) throw new PresentationError(404, `PPT_IMAGE_NOT_FOUND: ${key} 不存在或不属于当前用户`);
      const url = new URL(ref.url);
      // Storage URLs come from owner-scoped repositories, never model-supplied URLs.
      if (url.protocol !== "https:" || url.username || url.password) throw new PresentationError(400, "PPT 图片存储地址无效");
      if (!/^image\/(png|jpeg|webp|gif)$/.test(ref.contentType)) throw new PresentationError(400, "PPT 图片格式不支持，请使用 PNG、JPEG、WebP、GIF 或页面 SVG 元素");
      const response = await this.transport(new Request(url, { redirect: "error", signal: AbortSignal.timeout(30_000) }));
      if (!response.ok || !response.body) throw new PresentationError(502, `PPT_IMAGE_UNAVAILABLE: ${key} 图片尚不可读取`);
      const reader = response.body.getReader(), chunks: Uint8Array[] = [];
      let size = 0;
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          size += value.length; total += value.length;
          if (size > MAX_IMAGE || total > MAX_TOTAL) { await reader.cancel(); throw new PresentationError(413, "PPT 图片超过安全大小限制"); }
          chunks.push(value);
        }
      } finally { reader.releaseLock(); }
      const binary: string[] = [];
      for (const chunk of chunks) for (let offset = 0; offset < chunk.length; offset += 8192) binary.push(String.fromCharCode(...chunk.subarray(offset, offset + 8192)));
      if (!size) throw new PresentationError(502, `PPT_IMAGE_UNAVAILABLE: ${key} 图片为空`);
      assets.push({ key, contentType: ref.contentType, data: btoa(binary.join("")) });
    }
    return assets;
  }
}
