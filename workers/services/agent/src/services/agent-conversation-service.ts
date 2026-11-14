import type {
  AgentConversationListPage,
  AgentConversationRepository,
  AgentWorkspaceResult,
} from "../interfaces/agent-conversation-repository.js";
import type { AgentConversationRecord, AgentRunMessage } from "../interfaces/agent-run-repository.js";
import type { AgentAttachmentService } from "./agent-attachment-service.js";

function excerpt(value: string, length: number): string {
  return value.length <= length ? value : value.slice(0, length - 1) + "…";
}

export class AgentConversationService {
  constructor(
    private readonly repository: AgentConversationRepository,
    private readonly attachments?: AgentAttachmentService,
    private readonly mediaPublicBaseUrl?: string,
    private readonly imageUrls?: { refresh(userId: number, contents: string[]): Promise<string[]> },
  ) {}

  async create(userId: number, content: string): Promise<{ conversation: AgentConversationRecord; messages: [] } | null> {
    const title = excerpt(content.trim() || "请分析我上传的附件。", 40);
    const conversation = await this.repository.createConversation(userId, title);
    return conversation ? { conversation, messages: [] } : null;
  }

  rename(userId: number, conversationId: number, title: string) {
    return this.repository.rename(userId, conversationId, title);
  }

  async fork(userId: number, conversationId: number, messageId: number, key: string, content: string) {
    const row = await this.repository.fork(userId, conversationId, messageId, key, excerpt(content.trim(), 40));
    return row ? this.get(userId, row.id) : null;
  }

  async list(
    userId: number,
    page: number,
    size: number,
    search: string | null,
  ): Promise<AgentConversationListPage> {
    const offset = (page - 1) * size;
    const rows = await this.repository.listConversations(userId, size + 1, offset, search);
    const hasMore = rows.length > size;
    return {
      items: rows.slice(0, size).map((row) => this.withUnread(row)),
      page,
      size,
      hasMore,
    };
  }

  async get(userId: number, conversationId: number): Promise<{
    workspaceResults: AgentWorkspaceResult[];
    conversation: AgentConversationRecord & { unread: boolean };
    messages: unknown[];
  } | null> {
    const row = await this.repository.findOwnedConversation(userId, conversationId);
    if (!row) return null;
    const [messages, workspaceResults] = await Promise.all([
      this.messages(conversationId, row.userId),
      this.repository.listWorkspaceResults(conversationId),
    ]);
    const workspaceContent = await this.refreshImageContents(row.userId, workspaceResults.map(result => result.dataContent));
    return { conversation: this.withUnread(row), messages, workspaceResults: workspaceResults.map((result, index) => ({ ...result, dataContent: workspaceContent[index] })) };
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
    const content = await this.refreshArtifactContents(userId,
      await this.refreshImageContents(userId, rows.map(row => row.content)));
    return rows.map((row, index) => {
      return {
        id: row.id,
        conversationId: row.conversationId,
        runVersion: row.runVersion,
        role: row.role,
        kind: row.kind,
        toolName: row.toolName,
        invocationId: row.invocationId,
        content: content[index],
        attachments: refreshed[index] ?? [],
        createdAt: row.createdAt,
      };
    });
  }

  private refreshImageContents(userId: number, contents: string[]): Promise<string[]> {
    const normalized = contents.map(content => this.refreshMediaUrls(content));
    return this.imageUrls ? this.imageUrls.refresh(userId, normalized) : Promise.resolve(normalized);
  }

  /** Resolve owned document attachments again so history never depends on an expired signed URL. */
  private async refreshArtifactContents(userId: number, contents: string[]): Promise<string[]> {
    if (!this.attachments) return contents;
    const artifacts = contents.map(content => {
      try {
        const parsed: unknown = JSON.parse(content);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
        const row = parsed as Record<string, unknown>;
        const artifact = row.artifact && typeof row.artifact === "object" && !Array.isArray(row.artifact)
          ? row.artifact as Record<string, unknown> : row;
        return artifact.type === "artifact" && Array.isArray(artifact.files) ? { parsed, files: artifact.files } : null;
      } catch { return null; }
    });
    const positions: Array<{ file: Record<string, unknown>; id: number }> = [];
    for (const artifact of artifacts) for (const value of artifact?.files ?? []) {
      if (!value || typeof value !== "object" || Array.isArray(value)) continue;
      const file = value as Record<string, unknown>;
      if (typeof file.attachmentId === "number" && Number.isSafeInteger(file.attachmentId) && file.attachmentId > 0) {
        positions.push({ file, id: file.attachmentId });
      }
    }
    if (!positions.length) return contents;
    // One batch ownership lookup covers every file; avoid the five-upload limit per user message.
    const refreshed = await this.attachments.refreshStoredMany(userId, positions.map(value => [{ id: value.id }]));
    positions.forEach(({ file }, index) => {
      const reference = refreshed[index]?.[0];
      if (reference) { file.url = reference.url; file.contentType = reference.contentType; file.size = reference.size; }
      else delete file.url;
    });
    return contents.map((content, index) => artifacts[index] ? JSON.stringify(artifacts[index]!.parsed) : content);
  }

  /** Rewrite only our legacy public-image URLs; durable history remains unchanged. */
  private refreshMediaUrls(content: string): string {
    if (!this.mediaPublicBaseUrl) return content;
    const base = new URL(this.mediaPublicBaseUrl);
    if (base.protocol !== "https:" || base.username || base.password || base.search || base.hash) return content;
    return content.replace(
      /https:\/\/dinqor-content-automation\.dinqor\.workers\.dev\/api\/public\/blog\/media\/(?=[1-9]\d{0,17}\/\d{4}\/(?:0[1-9]|1[0-2])\/[0-9a-f-]{36}\.(?:png|jpg|webp|gif)(?:[\s"'<>\x60)\]\\]|$))/g,
      () => base.toString().replace(/\/+$/, "") + "/",
    );
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
