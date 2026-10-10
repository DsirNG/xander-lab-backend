export interface AgentAttachmentRecord {
  id: number;
  userId: number;
  objectKey: string;
  name: string;
  contentType: string;
  size: number;
  mediaAssetId: number | null;
  createdAt: string;
}

export interface AgentAttachmentReference {
  id: number;
  url: string;
  name: string;
  contentType: string;
  size: number;
  mediaAssetId?: number;
}

export interface AgentAttachmentRepository {
  create(input: Omit<AgentAttachmentRecord, "id" | "createdAt">): Promise<AgentAttachmentRecord | null>;
  findOwned(userId: number, id: number): Promise<AgentAttachmentRecord | null>;
  findOwnedMany(userId: number, ids: number[]): Promise<AgentAttachmentRecord[]>;
}

export interface AgentAttachmentStorage {
  write(objectKey: string, bytes: Uint8Array, contentType: string): Promise<void>;
  delete(objectKey: string): Promise<void>;
  signedReadUrl(objectKey: string): Promise<string>;
}
