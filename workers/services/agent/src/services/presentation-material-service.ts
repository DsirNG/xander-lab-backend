import type { AgentMediaAssetPort } from "../interfaces/agent-media-asset-port.js";
import type { PresentationMaterialCandidate, PresentationMaterialPort } from "../interfaces/presentation-material-port.js";

/** Imports a searched source and keeps provenance separate from generated imagery. */
export class PresentationMaterialService {
  constructor(private readonly source: PresentationMaterialPort, private readonly media: Pick<AgentMediaAssetPort, "uploadImage">) {}
  async import(userId: number, candidate: PresentationMaterialCandidate, signal: AbortSignal) {
    const image = await this.source.read(candidate, signal);
    signal.throwIfAborted();
    const asset = await this.media.uploadImage(userId, "presentation-source.png", image.contentType, image.bytes);
    return { assetId: asset.id, url: asset.url, contentType: image.contentType, width: image.width, height: image.height,
      sourceUrl: candidate.sourceUrl, originalImageUrl: candidate.url, description: candidate.description,
      provenance: "web-source", instruction: "Inspect the imported image before use. Add the actual sourceUrl to slide.sources; do not claim a generated image or assume licensing from a search result." };
  }
}
