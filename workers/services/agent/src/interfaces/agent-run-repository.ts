export interface AgentConversationRecord {
  id: number;
  userId: number;
  title: string;
  status: string;
  errorMessage: string | null;
  runVersion: number;
  cancelRequested: number;
  planJson: string | null;
  shareToken: string | null;
  isPinned: number;
  lastReadAt: string | null;
  lastCompletedAt: string | null;
  /** UTC start time of the current or most recently started run. */
  runStartedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AgentRunMessage {
  id: number;
  role: "user" | "assistant";
  content: string;
  attachmentsJson: string | null;
}

export interface AgentRunEvent {
  id: number;
  eventType: string;
  dataContent: string;
  createdAt?: string;
}

export type AgentSpecialMessageKind = "quiz" | "artifact";

export interface AgentTurnRepository {
  findOwnedConversation(userId: number, conversationId: number): Promise<AgentConversationRecord | null>;
  claimTurn(userId: number, conversationId: number, content: string, attachments: unknown[]): Promise<number | null>;
  recentMessages(conversationId: number): Promise<AgentRunMessage[]>;
  appendEvent(conversationId: number, runVersion: number, eventType: string, data: unknown): Promise<number>;
  saveSpecialMessage(
    userId: number,
    conversationId: number,
    runVersion: number,
    kind: AgentSpecialMessageKind,
    content: unknown,
  ): Promise<void>;
  finishTurn(userId: number, conversationId: number, runVersion: number, answer: string): Promise<{ completeEventId: number }>;
  failTurn(userId: number, conversationId: number, runVersion: number, message: string): Promise<number>;
  cancelTurn(userId: number, conversationId: number): Promise<AgentConversationRecord | null>;
  isCancellationRequested(userId: number, conversationId: number, runVersion: number): Promise<boolean>;
  listEventsAfter(conversationId: number, runVersion: number, afterEventId: number): Promise<AgentRunEvent[]>;
}
