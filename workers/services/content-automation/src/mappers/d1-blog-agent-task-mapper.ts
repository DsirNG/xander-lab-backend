import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  BlogAgentEvent, BlogAgentMessage, BlogAgentSessionView, BlogAgentSource,
  BlogAgentTask, BlogAgentTaskRepository, BlogAgentTaskView, BlogAgentVersion,
} from "../interfaces/blog-agent-repository.js";

interface TaskRow extends BlogAgentTask {}
interface MessageRow extends BlogAgentMessage {}
interface SourceRow extends BlogAgentSource {}
interface VersionRow extends BlogAgentVersion {}
interface EventRow extends BlogAgentEvent {}

const TASK_COLUMNS = `id, user_id AS userId, client_request_id AS clientRequestId, input,
  input_type AS inputType, audience, tone, status, stage, title, summary, content, outline,
  content_boundary AS contentBoundary, knowledge_graph_json AS knowledgeGraphJson,
  content_object_key AS contentObjectKey,
  category_id AS categoryId, tags_json AS tagsJson, review,
  illustration_status AS illustrationStatus, illustration_error AS illustrationError,
  published_post_id AS publishedPostId, error_message AS errorMessage,
  created_at AS createdAt, updated_at AS updatedAt`;

function parseObject(value: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(value);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {};
  } catch { return {}; }
}

function parseArray(value: string): string[] {
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) && parsed.every((item) => typeof item === "string") ? parsed : [];
  } catch { return []; }
}

export class D1BlogAgentTaskMapper implements BlogAgentTaskRepository {
  constructor(private readonly db: D1Binding) {}

  async create(userId: number, input: string, clientRequestId: string | null, now: string): Promise<number> {
    const created = await this.db.prepare(`
      INSERT INTO blog_agent_task (user_id, client_request_id, input, status, stage, created_at, updated_at)
      VALUES (?, ?, ?, 'created', 'analyze', ?, ?) RETURNING id
    `).bind(userId, clientRequestId, input, now, now).first<{ id: number }>();
    if (!created?.id) throw new Error("BlogAgent task insert returned no id");
    await this.insertMessage(created.id, "user", "message", null, input, now);
    return created.id;
  }

  findById(userId: number, taskId: number): Promise<BlogAgentTask | null> {
    return this.db.prepare(`SELECT ${TASK_COLUMNS} FROM blog_agent_task WHERE id = ? AND user_id = ?`)
      .bind(taskId, userId).first<TaskRow>();
  }

  findByRequestId(userId: number, clientRequestId: string): Promise<BlogAgentTask | null> {
    return this.db.prepare(`SELECT ${TASK_COLUMNS} FROM blog_agent_task WHERE user_id = ? AND client_request_id = ? LIMIT 1`)
      .bind(userId, clientRequestId).first<TaskRow>();
  }

  async list(userId: number, limit: number): Promise<BlogAgentTask[]> {
    const rows = await this.db.prepare(`SELECT ${TASK_COLUMNS} FROM blog_agent_task
      WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?`).bind(userId, limit).all<TaskRow>();
    return rows.results;
  }

  async hydrate(userId: number, taskId: number): Promise<BlogAgentTaskView | null> {
    const task = await this.findById(userId, taskId);
    if (!task) return null;
    const [sources, versions, messages] = await Promise.all([
      this.db.prepare(`SELECT id, task_id AS taskId, title, url, publisher, excerpt, reliability,
        retrieved_at AS retrievedAt FROM blog_agent_source WHERE task_id = ? ORDER BY id`).bind(taskId).all<SourceRow>(),
      this.db.prepare(`SELECT id, task_id AS taskId, version_no AS versionNo, title, summary, content,
        content_object_key AS contentObjectKey, change_note AS changeNote, created_at AS createdAt FROM blog_agent_version
        WHERE task_id = ? ORDER BY version_no DESC`).bind(taskId).all<VersionRow>(),
      this.db.prepare(`SELECT id, task_id AS taskId, role, kind, stage, content, created_at AS createdAt
        FROM blog_agent_message WHERE task_id = ? ORDER BY id`).bind(taskId).all<MessageRow>(),
    ]);
    return {
      task, tags: parseArray(task.tagsJson), contentBoundary: parseObject(task.contentBoundary),
      knowledgeGraph: parseObject(task.knowledgeGraphJson), sources: sources.results,
      versions: versions.results, illustrations: [], messages: messages.results,
    };
  }

  async claim(userId: number, taskId: number, mode: "initial" | "revision", now: string): Promise<boolean> {
    const expected = mode === "initial" ? "created" : "ready";
    const stage = mode === "initial" ? "analyze" : "write";
    const result = await this.db.prepare(`UPDATE blog_agent_task SET status = 'running', stage = ?,
      error_message = NULL, updated_at = ? WHERE id = ? AND user_id = ? AND status = ?`)
      .bind(stage, now, taskId, userId, expected).run();
    return result.meta.changes === 1;
  }

  async updateStage(userId: number, taskId: number, stage: string, message: string, now: string): Promise<boolean> {
    const result = await this.db.prepare(`UPDATE blog_agent_task SET stage = ?, updated_at = ?
      WHERE id = ? AND user_id = ? AND status = 'running'`).bind(stage, now, taskId, userId).run();
    if (result.meta.changes !== 1) return false;
    await this.insertMessage(taskId, "assistant", "process", stage, message, now);
    return true;
  }

  async fail(userId: number, taskId: number, message: string, revision: boolean, now: string): Promise<void> {
    await this.db.prepare(`UPDATE blog_agent_task SET status = ?, stage = ?, error_message = ?, updated_at = ?
      WHERE id = ? AND user_id = ? AND status = 'running'`)
      .bind(revision ? "ready" : "failed", revision ? "review" : "analyze", message.slice(0, 1_000), now, taskId, userId).run();
    if (revision) await this.insertMessage(taskId, "assistant", "process", "review", "本轮修改失败，已保留上一版文章", now);
  }

  async complete(
    userId: number, taskId: number, article: Record<string, unknown>, review: Record<string, unknown>,
    contentObjectKey: string | null, now: string,
  ): Promise<boolean> {
    const content = typeof review.content === "string" && review.content.trim()
      ? review.content.trim() : String(article.content ?? "");
    const title = String(article.title ?? "").trim();
    const summary = String(article.summary ?? content.slice(0, 159)).trim();
    const updated = await this.db.prepare(`UPDATE blog_agent_task SET
      input_type = ?, audience = ?, tone = ?, title = ?, summary = ?, content = ?, content_object_key = ?, outline = ?,
      content_boundary = ?, knowledge_graph_json = ?, category_id = ?, tags_json = ?, review = ?,
      status = 'ready', stage = 'review', error_message = NULL, updated_at = ?
      WHERE id = ? AND user_id = ? AND status = 'running' RETURNING id`)
      .bind(
        normalizeInputType(article.writingBrief && typeof article.writingBrief === "object"
          ? (article.writingBrief as Record<string, unknown>).inputNature : null),
        limitText(article.writingBrief && typeof article.writingBrief === "object"
          ? (article.writingBrief as Record<string, unknown>).audience : null, 120, "广泛读者"),
        limitText(article.writingBrief && typeof article.writingBrief === "object"
          ? (article.writingBrief as Record<string, unknown>).tone : null, 60, "清晰、准确、可读"),
        title.slice(0, 255), summary.slice(0, 2_000),
        contentObjectKey ? null : content.replace(/^\s*<!-- illustration:[a-z0-9-]+ -->\s*$/gim, "").trim(),
        contentObjectKey,
        typeof article.outline === "string" ? article.outline.slice(0, 50_000) : "",
        JSON.stringify(isObject(article.contentBoundary) ? article.contentBoundary : {}),
        JSON.stringify(isObject(article.knowledgeGraph) ? article.knowledgeGraph : {}),
        ["frontend", "backend", "architecture", "devops", "career"].includes(String(article.categoryId))
          ? String(article.categoryId) : "career",
        JSON.stringify(normalizeTags(article.tags)),
        typeof review.review === "string" ? review.review.slice(0, 10_000) : "",
        now, taskId, userId,
      ).first<{ id: number }>();
    if (!updated) return false;

    const batch = [this.db.prepare("DELETE FROM blog_agent_source WHERE task_id = ?").bind(taskId)];
    for (const source of normalizeSources(article.sources)) {
      batch.push(this.db.prepare(`INSERT INTO blog_agent_source
        (task_id, title, url, publisher, excerpt, reliability, retrieved_at) VALUES (?, ?, ?, ?, ?, ?, ?)`)
        .bind(taskId, source.title, source.url, source.publisher, source.excerpt, source.reliability, now));
    }
    const version = await this.db.prepare("SELECT COUNT(*) AS count FROM blog_agent_version WHERE task_id = ?")
      .bind(taskId).first<{ count: number }>();
    batch.push(this.db.prepare(`INSERT INTO blog_agent_version
      (task_id, version_no, title, summary, content, content_object_key, change_note, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)`)
      .bind(taskId, (version?.count ?? 0) + 1, title, summary,
        contentObjectKey ? "" : content, contentObjectKey,
        "博客生成助手完成调研、写作与审校", now));
    batch.push(this.db.prepare(`INSERT INTO blog_agent_message (task_id, role, kind, stage, content, created_at)
      VALUES (?, 'assistant', 'result', 'review', ?, ?)`).bind(taskId, title, now));
    await this.db.batch(batch);
    return true;
  }

  async publish(userId: number, taskId: number, postId: number, now: string): Promise<void> {
    const result = await this.db.prepare(`UPDATE blog_agent_task SET published_post_id = ?, updated_at = ?
      WHERE id = ? AND user_id = ? AND status = 'ready' AND published_post_id IS NULL`)
      .bind(postId, now, taskId, userId).run();
    if (result.meta.changes === 0) {
      const existing = await this.findById(userId, taskId);
      if (existing?.publishedPostId !== postId) throw new Error("BlogAgent publish state changed");
    }
  }

  async appendEvent(taskId: number, eventType: string, data: string, now: string): Promise<number> {
    const result = await this.db.prepare(`INSERT INTO blog_agent_event (task_id, event_type, data_content, created_at)
      VALUES (?, ?, ?, ?) RETURNING id`).bind(taskId, eventType, data, now).first<{ id: number }>();
    if (!result?.id) throw new Error("BlogAgent event insert returned no id");
    return result.id;
  }

  async listEventsAfter(taskId: number, cursor: number, limit: number): Promise<BlogAgentEvent[]> {
    const result = await this.db.prepare(`SELECT id, task_id AS taskId, event_type AS eventType,
      data_content AS dataContent, created_at AS createdAt FROM blog_agent_event
      WHERE task_id = ? AND id > ? ORDER BY id LIMIT ?`).bind(taskId, cursor, limit).all<EventRow>();
    return result.results;
  }

  async recoverStale(userId: number, taskId: number, cutoff: string, now: string): Promise<void> {
    const current = await this.findById(userId, taskId);
    if (!current || current.status !== "running" || current.updatedAt > cutoff) return;
    const version = await this.db.prepare("SELECT COUNT(*) AS count FROM blog_agent_version WHERE task_id = ?")
      .bind(taskId).first<{ count: number }>();
    const hasVersion = (version?.count ?? 0) > 0;
    await this.db.prepare(`UPDATE blog_agent_task SET status = ?, error_message = ?, updated_at = ?
      WHERE id = ? AND user_id = ? AND status = 'running' AND updated_at <= ?`)
      .bind(hasVersion ? "ready" : "failed",
        hasVersion ? "任务执行中断，已恢复到上一版已完成文章" : "任务执行中断，请重新创建任务",
        now, taskId, userId, cutoff).run();
  }

  private async insertMessage(taskId: number, role: string, kind: string, stage: string | null, content: string, now: string) {
    await this.db.prepare(`INSERT INTO blog_agent_message (task_id, role, kind, stage, content, created_at)
      VALUES (?, ?, ?, ?, ?, ?)`).bind(taskId, role, kind, stage, content, now).run();
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === "object" && !Array.isArray(value));
}

function limitText(value: unknown, max: number, fallback: string): string {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, max) : fallback;
}

function normalizeInputType(value: unknown): string {
  return ["topic", "project_context", "journal"].includes(String(value)) ? String(value) : "topic";
}

function normalizeTags(value: unknown): string[] {
  return Array.isArray(value) ? [...new Set(value.filter((item): item is string => typeof item === "string")
    .map((item) => item.trim().slice(0, 100)).filter(Boolean))].slice(0, 8) : [];
}

function normalizeSources(value: unknown): Array<{ title: string; url: string; publisher: string | null; excerpt: string | null; reliability: string | null }> {
  if (!Array.isArray(value)) return [];
  const result = [];
  for (const item of value) {
    if (!isObject(item) || typeof item.url !== "string" || !/^https?:\/\//i.test(item.url) || item.url.length > 2_000) continue;
    result.push({ title: limitText(item.title, 500, item.url), url: item.url,
      publisher: typeof item.publisher === "string" ? item.publisher.slice(0, 255) : null,
      excerpt: typeof item.excerpt === "string" ? item.excerpt.slice(0, 10_000) : null,
      reliability: typeof item.reliability === "string" ? item.reliability.slice(0, 64) : null });
    if (result.length === 50) break;
  }
  return result;
}
