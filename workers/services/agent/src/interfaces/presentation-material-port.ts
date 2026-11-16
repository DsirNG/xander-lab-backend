export interface PresentationMaterialCandidate { url: string; sourceUrl: string; description: string }
export interface PresentationMaterialPort {
  read(candidate: PresentationMaterialCandidate, signal: AbortSignal): Promise<{ bytes: Uint8Array; contentType: "image/png"; width: number; height: number }>;
}
