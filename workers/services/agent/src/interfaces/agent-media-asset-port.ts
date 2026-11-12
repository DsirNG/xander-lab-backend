export interface AgentMediaAssetPort {
  uploadImage(userId: number, originalName: string, contentType: string, bytes: Uint8Array): Promise<{ id: number; url: string }>;
  findOwned(userId: number, id: number): Promise<{ id: number; url: string } | null>;
  deleteAsset(userId: number, id: number): Promise<void>;
}
