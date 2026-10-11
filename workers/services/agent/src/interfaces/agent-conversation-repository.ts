import type { AgentConversationRecord } from "./agent-run-repository.js";

export interface AgentMessageRecord {
  id: number;
  conversationId: number;
  runVersion: number | null;
  role: string;
  kind: string;
  toolName: string | null;
  invocationId: string | null;
  content: string;
  attachmentsJson: string | null;
  createdAt: string;
}

export interface AgentConversationListPage {
  items: Array<AgentConversationRecord & { unread: boolean }>;
  page: number;
  size: number;
  hasMore: boolean;
}

export interface AgentConversationRepository {
  createConversation(userId: number, title: string): Promise<AgentConversationRecord | null>;
  listConversations(
    userId: number,
    limit: number,
    offset: number,
    search: string | null,
  ): Promise<AgentConversationRecord[]>;
  findOwnedConversation(userId: number, conversationId: number): Promise<AgentConversationRecord | null>;
  listMessages(conversationId: number): Promise<AgentMessageRecord[]>;
  findSharedConversation(shareToken: string): Promise<AgentConversationRecord | null>;
  updateReadTime(userId: number, conversationId: number, value: string): Promise<AgentConversationRecord | null>;
  updatePinned(userId: number, conversationId: number, pinned: boolean): Promise<AgentConversationRecord | null>;
  enableShare(userId: number, conversationId: number, candidate: string): Promise<string | null | undefined>;
  disableShare(userId: number, conversationId: number): Promise<boolean>;
}
