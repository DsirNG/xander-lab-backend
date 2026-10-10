import type { AgentConversationRepository } from "../interfaces/agent-conversation-repository.js";
import type { AgentConversationRecord, AgentRunMessage } from "../interfaces/agent-run-repository.js";
import type { AgentAttachmentService } from "./agent-attachment-service.js";

function excerpt(value: string, length: number): string {
  return value.length <= length ? value : value.slice(0, length - 1) + "…";
}

export class AgentConversationService {
  constructor(
    private readonly repository: AgentConversationRepository,
    private readonly attachments?: AgentAttachmentService,
  ) {}

  async create(userId: number, content: string): Promise<{ conversation: AgentConversationRecord; messages: [] } | null> {
    const title = excerpt(content.trim() || "请分析我上传的附件。", 40);
    const conversation = await this.repository.createConversation(userId, title);
    return conversation ? { conversation, messages: [] } : null;
  }

  async list(userId: number): Promise<AgentConversationRecord[]> {
    const rows = await this.repository.listConversations(userId);
    return rows.map((row) => this.withUnread(row));
  }

  async get(userId: number, conversationId: number): Promise<{
    conversation: AgentConversationRecord & { unread: boolean };
    messages: unknown[];
  } | null> {
    const row = await this.repository.findOwnedConversation(userId, conversationId);
    if (!row) return null;
    return { conversation: this.withUnread(row), messages: await this.messages(conversationId, row.userId) };
  }

  async listMessages(userId: number, conversationId: number): Promise<unknown[] | null> {
    if (!await this.repository.findOwnedConversation(userId, conversationId)) return null;
    return this.messages(conversationId, userId);
  }

  async getShared(shareToken: string): Promise<{
    conversation: AgentConversationRecord & { unread: boolean };
    messages: unknown[];
  } | null> {
    const row = await this.repository.findSharedConversation(shareToken);
    if (!row) return null;
    return { conversation: this.withUnread(row), messages: await this.messages(row.id, row.userId) };
  }

  async markRead(userId: number, conversationId: number): Promise<AgentConversationRecord | null> {
    const row = await this.repository.updateReadTime(userId, conversationId, new Date().toISOString());
    return row ? this.withUnread(row) : null;
  }

  async setPinned(userId: number, conversationId: number, pinned: boolean): Promise<AgentConversationRecord | null> {
    const row = await this.repository.updatePinned(userId, conversationId, pinned);
    return row ? this.withUnread(row) : null;
  }

  enableShare(userId: number, conversationId: number): Promise<string | null | undefined> {
    return this.repository.enableShare(userId, conversationId, crypto.randomUUID().replaceAll("-", ""));
  }

  disableShare(userId: number, conversationId: number): Promise<boolean> {
    return this.repository.disableShare(userId, conversationId);
  }

  private async messages(conversationId: number, userId: number): Promise<unknown[]> {
    const rows = await this.repository.listMessages(conversationId);
    const attachmentValues = rows.map((row) => {
      let attachments: unknown[] = [];
      if (row.attachmentsJson) {
        try {
          const parsed: unknown = JSON.parse(row.attachmentsJson);
          if (Array.isArray(parsed)) attachments = parsed;
        } catch {
          attachments = [];
        }
      }
      return attachments;
    });
    const refreshed = this.attachments
      ? await this.attachments.refreshStoredMany(userId, attachmentValues)
      : attachmentValues;
    return rows.map((row, index) => {
      return {
        id: row.id,
        conversationId: row.conversationId,
        runVersion: row.runVersion,
        role: row.role,
        kind: row.kind,
        toolName: row.toolName,
        invocationId: row.invocationId,
        content: row.content,
        attachments: refreshed[index] ?? [],
        createdAt: row.createdAt,
      };
    });
  }

  private withUnread<T extends AgentConversationRecord>(row: T): T & { unread: boolean } {
    const completedAt = row.lastCompletedAt ? Date.parse(row.lastCompletedAt) : NaN;
    const readAt = row.lastReadAt ? Date.parse(row.lastReadAt) : NaN;
    return {
      ...row,
      unread: Number.isFinite(completedAt) && (!Number.isFinite(readAt) || readAt < completedAt),
    };
  }
}
