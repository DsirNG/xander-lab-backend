export interface AgentImageAsset {
  id: number;
  url: string;
  width: number | null;
  height: number | null;
  contentType: string;
  originalName?: string;
  size?: number;
}

export interface AgentImageMediaPort {
  recordGenerated(
    userId: number,
    originalName: string,
    contentType: string,
    bytes: Uint8Array,
    generationMeta: string,
  ): Promise<AgentImageAsset>;
  findOwned(userId: number, id: number): Promise<AgentImageAsset | null>;
}
