export interface AgentMediaAssetPort {
  uploadImage(userId: number, originalName: string, contentType: string, bytes: Uint8Array): Promise<{ id: number }>;
  deleteAsset(userId: number, id: number): Promise<void>;
}
