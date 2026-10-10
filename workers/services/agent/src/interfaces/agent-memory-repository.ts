export interface AgentMemoryRow {
  id: number;
  userId: number;
  memoryKey: string;
  kind: string;
  content: string;
  sourceConversationId: number | null;
  hitCount: number;
  lastUsedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AgentMemoryWrite {
  userId: number;
  memoryKey: string;
  kind: string;
  content: string;
  sourceConversationId: number | null;
}

export interface AgentMemoryRepository {
  upsert(input: AgentMemoryWrite): Promise<AgentMemoryRow | null>;
  delete(userId: number, memoryKey: string): Promise<boolean>;
  list(userId: number, limit: number): Promise<AgentMemoryRow[]>;
  markRecalled(userId: number, ids: number[], usedAt: string): Promise<void>;
}
