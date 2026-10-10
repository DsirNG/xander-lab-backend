import type { AgentMemoryRepository, AgentMemoryRow } from "../interfaces/agent-memory-repository.js";

const MAX_MEMORIES = 200;
const RECALL_LIMIT = 8;
const MAX_CONTENT_LENGTH = 2000;
const MAX_KEY_LENGTH = 120;
const KINDS = new Set(["PROFILE", "PREFERENCE", "FACT", "PROJECT"]);

export class AgentMemoryError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AgentMemoryError";
  }
}

function compareDate(left: string | null, right: string | null): number {
  if (left === null && right === null) return 0;
  if (left === null) return -1;
  if (right === null) return 1;
  return left.localeCompare(right);
}

function normalizeTerms(query: string): string[] {
  const terms: string[] = [];
  const tokens = query.toLowerCase().match(/[\p{Script=Han}\p{L}\p{N}]+/gu) ?? [];
  for (const token of tokens) {
    if (/\p{Script=Han}/u.test(token)) {
      const points = Array.from(token);
      if (points.length === 1) terms.push(token);
      else for (let index = 0; index + 1 < points.length; index++) terms.push(points[index] + points[index + 1]);
    } else if (token.length >= 2) {
      terms.push(token);
    }
  }
  return terms;
}

function relevance(row: AgentMemoryRow, terms: string[]): number {
  if (terms.length === 0) return row.kind === "PROFILE" || row.kind === "PREFERENCE" ? 1 : 0;
  const haystack = `${row.memoryKey} ${row.content}`.toLowerCase();
  let score = 0;
  for (const term of terms) if (haystack.includes(term)) score += term.length;
  if (row.kind === "PROFILE" || row.kind === "PREFERENCE") score += 1;
  return score;
}

function limitedText(value: string | null | undefined, length: number): string {
  const normalized = (value ?? "").trim();
  return normalized.length <= length ? normalized : `${normalized.slice(0, length)}…`;
}

export class AgentMemoryService {
  constructor(private readonly repository: AgentMemoryRepository) {}

  async remember(userId: number, memoryKey: string, kind: string, content: string, conversationId: number) {
    const key = this.normalizeKey(memoryKey);
    const body = content.trim();
    if (!key) throw new AgentMemoryError("记忆需要 memoryKey");
    if (!body) throw new AgentMemoryError("记忆需要 content");
    if (body.length > MAX_CONTENT_LENGTH) throw new AgentMemoryError(`单条记忆不能超过 ${MAX_CONTENT_LENGTH} 个字符`);
    const normalizedKind = KINDS.has(kind.trim().toUpperCase()) ? kind.trim().toUpperCase() : "FACT";
    const row = await this.repository.upsert({
      userId, memoryKey: key, kind: normalizedKind, content: body, sourceConversationId: conversationId,
    });
    if (!row) throw new AgentMemoryError("长期记忆保存失败");
    return row;
  }

  async forget(userId: number, memoryKey: string): Promise<boolean> {
    const key = this.normalizeKey(memoryKey);
    if (!key) throw new AgentMemoryError("删除记忆需要 memoryKey");
    return this.repository.delete(userId, key);
  }

  async recall(userId: number, query: string, limit = RECALL_LIMIT): Promise<AgentMemoryRow[]> {
    const all = await this.repository.list(userId, MAX_MEMORIES);
    if (all.length === 0) return [];
    const size = Math.max(1, Math.min(RECALL_LIMIT, Number.isSafeInteger(limit) ? limit : RECALL_LIMIT));
    const terms = normalizeTerms(query);
    const ranked = all.map((row) => ({ row, score: relevance(row, terms) }))
      .filter((entry) => terms.length === 0 || entry.score > 0)
      .sort((left, right) => right.score - left.score
        || right.row.hitCount - left.row.hitCount
        || compareDate(right.row.updatedAt, left.row.updatedAt));
    const recalled = ranked.slice(0, size).map((entry) => entry.row);
    try {
      await this.repository.markRecalled(userId, recalled.map((row) => row.id), new Date().toISOString());
    } catch {
      // Recall remains available when its secondary hit counter cannot be updated.
    }
    return recalled;
  }

  async context(userId: number, query: string): Promise<string> {
    const memories = await this.recall(userId, query);
    if (memories.length === 0) return "";
    return memories.map((memory) => `- [${memory.kind} ${memory.memoryKey}] ${memory.content}`).join("\n");
  }

  async executeRemember(userId: number, conversationId: number, args: unknown) {
    const input = this.record(args);
    const action = typeof input.action === "string" ? input.action.trim().toLowerCase() : "upsert";
    const memoryKey = typeof input.memoryKey === "string" ? input.memoryKey : "";
    if (action === "delete") {
      const removed = await this.forget(userId, memoryKey);
      return {
        data: { ok: true, action: "delete", removed },
        answer: removed ? `已删除长期记忆「${this.normalizeKey(memoryKey)}」。`
          : `没有找到长期记忆「${this.normalizeKey(memoryKey)}」，我没有删除任何内容。`,
      };
    }
    if (action !== "upsert") throw new AgentMemoryError("记忆操作格式不正确");
    const kind = typeof input.kind === "string" ? input.kind : "FACT";
    const content = typeof input.content === "string" ? input.content : "";
    const saved = await this.remember(userId, memoryKey, kind, content, conversationId);
    return {
      data: { ok: true, action: "upsert", memoryKey: saved.memoryKey, kind: saved.kind },
      answer: `已记住「${saved.memoryKey}」。我会在后续对话中参考。`,
    };
  }

  async executeRecall(userId: number, args: unknown) {
    const input = this.record(args);
    const query = typeof input.query === "string" ? input.query : "";
    const limit = typeof input.limit === "number" ? input.limit : RECALL_LIMIT;
    const memories = await this.recall(userId, query, limit);
    const items = memories.map((memory) => ({
      memoryKey: memory.memoryKey,
      kind: memory.kind,
      content: memory.content,
      updatedAt: memory.updatedAt,
    }));
    const answer = items.length === 0
      ? "长期记忆里没有相关记录。我可以在你说明后帮你记下来。"
      : items.map((item) => `- [${item.kind} ${item.memoryKey}] ${item.content}`).join("\n");
    return { data: { ok: true, matched: items.length, items }, answer };
  }

  private record(value: unknown): Record<string, unknown> {
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown> : {};
  }

  private normalizeKey(value: string): string {
    return value.trim().toLowerCase().replace(/\s+/g, "-").slice(0, MAX_KEY_LENGTH);
  }
}
