export interface BlogMediaAsset {
  id: number;
  userId: number;
  url: string;
  originalName: string;
  storedName: string | null;
  size: number;
  contentType: string;
  width: number | null;
  height: number | null;
  sourceType: string;
  agentTaskId: number | null;
  generationMeta: string | null;
  createdAt: string;
}

export interface BlogMediaInsert {
  userId: number;
  url: string;
  originalName: string;
  storedName: string | null;
  size: number;
  contentType: string;
  width?: number | null;
  height?: number | null;
  sourceType: "user_upload" | "agent_generated";
  agentTaskId?: number | null;
  generationMeta?: string | null;
}

export interface BlogMediaRepository {
  listImages(userId: number, contentType: string | null, keyword: string | null, limit: number): Promise<BlogMediaAsset[]>;
  countAgentImages(userId: number): Promise<number>;
  listAgentImages(userId: number, limit: number, offset: number): Promise<BlogMediaAsset[]>;
  findOwned(userId: number, id: number): Promise<BlogMediaAsset | null>;
  listByTask(userId: number, taskId: number): Promise<BlogMediaAsset[]>;
  listRecentGenerated(userId: number, limit: number): Promise<BlogMediaAsset[]>;
  findPublicByStoredName(storedName: string): Promise<BlogMediaAsset | null>;
  insert(input: BlogMediaInsert): Promise<BlogMediaAsset>;
  deleteOwned(userId: number, id: number): Promise<boolean>;
}
