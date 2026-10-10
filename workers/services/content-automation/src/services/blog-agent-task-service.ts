import type { BlogPlanModelPort, BlogPlanModelResponse } from "../interfaces/blog-plan-model-port.js";
import type { BlogAgentTaskRepository, BlogAgentTaskView, BlogAgentTask } from "../interfaces/blog-agent-repository.js";
import type { BlogPostCreationService } from "./blog-post-creation-service.js";
import type { BlogPublicService } from "./blog-public-service.js";
import type { ArticleContentStorage } from "../interfaces/blog-post-creation.js";

export class BlogAgentTaskError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "BlogAgentTaskError";
  }
}

export type BlogAgentEventObserver = (eventId: number, eventType: string, data: string) => void | Promise<void>;

function object(response: BlogPlanModelResponse): Record<string, unknown> {
  let value: unknown;
  try { value = JSON.parse(response.payloadJson); }
  catch { throw new Error("博客模型阶段结果不是有效 JSON"); }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("博客模型阶段结果格式无效");
  return value as Record<string, unknown>;
}

function text(value: unknown, max: number, fallback = ""): string {
  return typeof value === "string" ? value.trim().slice(0, max) : fallback;
}

function normalizeArticle(value: Record<string, unknown>): Record<string, unknown> {
  const title = text(value.title, 255);
  const content = text(value.content, 1_800_000).replace(/^\s*<!-- illustration:[a-z0-9-]+ -->\s*$/gim, "").trim();
  if (!title || !content) throw new Error("模型未返回可用的文章标题或正文");
  return { ...value, title, content,
    summary: text(value.summary, 2_000, content.slice(0, 159)),
    categoryId: ["frontend", "backend", "architecture", "devops", "career"].includes(String(value.categoryId))
      ? String(value.categoryId) : "career" };
}

export class BlogAgentTaskService {
  constructor(
    private readonly repository: BlogAgentTaskRepository,
    private readonly model: BlogPlanModelPort,
    private readonly posts: BlogPostCreationService,
    private readonly publicPosts: BlogPublicService,
    private readonly contentStorage?: ArticleContentStorage,
    private readonly now: () => number = Date.now,
  ) {}

  async create(userId: number, inputValue: unknown, requestIdValue?: unknown): Promise<BlogAgentTask> {
    if (!Number.isSafeInteger(userId) || userId < 1 || typeof inputValue !== "string"
      || !inputValue.trim() || inputValue.length > 20_000) {
      throw new BlogAgentTaskError(400, "请输入主题或日记");
    }
    const requestId = requestIdValue === undefined || requestIdValue === null || requestIdValue === ""
      ? null : typeof requestIdValue === "string" ? requestIdValue.trim() : "invalid";
    if (requestId !== null && (!requestId || requestId.length > 64)) {
      throw new BlogAgentTaskError(400, "请求幂等标识不能超过 64 个字符");
    }
    if (requestId) {
      const existing = await this.repository.findByRequestId(userId, requestId);
      if (existing) return existing;
    }
    try {
      const id = await this.repository.create(userId, inputValue.trim(), requestId, new Date(this.now()).toISOString());
      const task = await this.repository.findById(userId, id);
      if (!task) throw new Error("BlogAgent task disappeared after insert");
      return task;
    } catch (error) {
      if (requestId) {
        const existing = await this.repository.findByRequestId(userId, requestId);
        if (existing) return existing;
      }
      throw error;
    }
  }

  async list(userId: number) {
    const tasks = await this.repository.list(userId, 50);
    return tasks.map((task) => ({
      id: task.id,
      title: task.title?.trim() || excerpt(task.input, 36),
      input: task.input,
      status: task.status,
      stage: task.stage,
      createdAt: task.createdAt,
      updatedAt: task.updatedAt,
    }));
  }

  async detail(userId: number, taskId: number): Promise<BlogAgentTaskView> {
    await this.repository.recoverStale(userId, taskId,
      new Date(this.now() - 30 * 60_000).toISOString(), new Date(this.now()).toISOString());
    const view = await this.repository.hydrate(userId, taskId);
    if (!view) throw new BlogAgentTaskError(404, "博客生成助手任务不存在");
    await this.hydrateContent(view);
    return view;
  }

  async findReadyByClientRequestId(userId: number, requestId: unknown): Promise<BlogAgentTask | null> {
    if (typeof requestId !== "string" || !requestId.trim() || requestId.length > 64) return null;
    const task = await this.repository.findByRequestId(userId, requestId.trim());
    return task?.status === "ready" ? task : null;
  }

  async prepare(userId: number, taskId: number, mode: "initial" | "revision"): Promise<void> {
    const task = await this.repository.findById(userId, taskId);
    if (!task) throw new BlogAgentTaskError(404, "博客生成助手任务不存在");
    const expected = mode === "initial" ? "created" : "ready";
    const currentContent = await this.readTaskContent(task);
    if (mode === "revision" && (!currentContent || !currentContent.trim())) {
      throw new BlogAgentTaskError(409, "当前文章尚未准备好，不能修改");
    }
    if (!await this.repository.claim(userId, taskId, mode, new Date(this.now()).toISOString())) {
      const latest = await this.repository.findById(userId, taskId);
      if (latest?.status === "running") throw new BlogAgentTaskError(409,
        mode === "initial" ? "任务正在执行，请勿重复启动" : "任务正在执行，请勿重复提交修改");
      if (latest?.status !== expected) throw new BlogAgentTaskError(409, "当前任务状态不能启动");
      throw new BlogAgentTaskError(409, "任务状态已变化，请重试");
    }
    await this.repository.appendEvent(taskId, "reset", "", new Date(this.now()).toISOString());
  }

  async runClaimed(userId: number, taskId: number, mode: "initial" | "revision", instruction: string | null,
    observer?: BlogAgentEventObserver): Promise<BlogAgentTaskView> {
    let lastStage = mode === "initial" ? "analyze" : "write";
    const emit = async (eventType: string, data: string) => {
      const id = await this.repository.appendEvent(taskId, eventType, data, new Date(this.now()).toISOString());
      await observer?.(id, eventType, data);
    };
    try {
      const task = await this.repository.findById(userId, taskId);
      if (!task || task.status !== "running") throw new BlogAgentTaskError(404, "博客生成助手任务不存在");
      const stage = async (name: string, message: string) => {
        lastStage = name;
        if (!await this.repository.updateStage(userId, taskId, name, message, new Date(this.now()).toISOString())) {
          throw new Error("博客生成助手执行权已失效");
        }
        await emit("stage", `${name}|${message}`);
      };
      let article: Record<string, unknown>;
      if (mode === "initial") {
        await stage("analyze", "正在理解你的目标和文章边界");
        const analysis = object(await this.model.runStage("analyze", { input: task.input }));
        await stage("research", "正在联网查找并核验相关资料");
        const research = object(await this.model.runStage("research", {
          input: task.input, analysisJson: JSON.stringify(analysis),
        }));
        await stage("write", "正在根据策划和调研结果撰写文章草稿");
        article = normalizeArticle(object(await this.model.runStage("article", {
          input: `${task.input}\n\n策划结果：\n${JSON.stringify(analysis)}\n\n调研结果：\n${JSON.stringify(research)}`,
        })));
      } else {
        if (!instruction?.trim()) throw new BlogAgentTaskError(400, "修改要求不能为空");
        await stage("write", "正在根据你的新要求修改文章");
        article = normalizeArticle(object(await this.model.runStage("revise", {
          content: await this.readTaskContent(task), instruction: instruction.trim(),
        })));
      }
      await stage("review", mode === "initial" ? "正在进行最终逻辑与表达审校" : "正在检查本轮修改");
      const review = object(await this.model.runStage("review", {
        content: String(article.content), request: mode === "initial" ? task.input : instruction,
      }));
      let contentObjectKey: string | null = null;
      const content = typeof review.content === "string" && review.content.trim()
        ? review.content.trim().replace(/^\s*<!-- illustration:[a-z0-9-]+ -->\s*$/gim, "").trim()
        : String(article.content ?? "");
      const bytes = new TextEncoder().encode(content).byteLength;
      if (bytes > 256 * 1024) {
        if (!this.contentStorage) throw new Error("文章正文超过 D1 内嵌上限，OSS 存储尚未配置");
        contentObjectKey = `blog-agent/${userId}/tasks/${taskId}/${crypto.randomUUID()}.md`;
        await this.contentStorage.write(contentObjectKey, content);
      }
      try {
        if (!await this.repository.complete(userId, taskId, article, { ...review, content }, contentObjectKey,
          new Date(this.now()).toISOString())) {
          throw new Error("博客生成助手执行权已失效");
        }
      } catch (error) {
        if (contentObjectKey && this.contentStorage) {
          try { await this.contentStorage.delete(contentObjectKey); } catch { /* OSS lifecycle handles failed compensation. */ }
        }
        throw error;
      }
      
      const view = await this.detail(userId, taskId);
      const completion = {
        ...view,
        task: { ...view.task, content: null },
        versions: view.versions.map((version) => ({ ...version, content: "" })),
      };
      await emit("complete", JSON.stringify(completion));
      return view;
    } catch (error) {
      const message = (error instanceof Error ? error.message : "博客生成助手执行失败").slice(0, 1_000);
      await this.repository.fail(userId, taskId, message, mode === "revision", new Date(this.now()).toISOString());
      await emit("error", message);
      throw error;
    }
  }

  async run(userId: number, taskId: number, observer?: BlogAgentEventObserver) {
    await this.prepare(userId, taskId, "initial");
    return this.runClaimed(userId, taskId, "initial", null, observer);
  }

  async revise(userId: number, taskId: number, instruction: string, observer?: BlogAgentEventObserver) {
    if (typeof instruction !== "string" || !instruction.trim() || instruction.length > 20_000) {
      throw new BlogAgentTaskError(400, "修改要求不能为空");
    }
    await this.prepare(userId, taskId, "revision");
    return this.runClaimed(userId, taskId, "revision", instruction.trim(), observer);
  }

  async publish(userId: number, taskId: number) {
    const view = await this.detail(userId, taskId);
    const task = view.task;
    if (task.status !== "ready") throw new BlogAgentTaskError(409, "文章尚未生成完成");
    if (task.publishedPostId) {
      const existing = await this.publicPosts.findPublishedPost(task.publishedPostId);
      if (existing) return existing;
    }
    const post = await this.posts.create({
      userId, idempotencyKey: `blog-agent-task-${taskId}`,
      title: task.title || "未命名文章", summary: task.summary || excerpt(task.content ?? "", 160),
      content: task.content ?? "", categoryId: task.categoryId || "career", tags: view.tags, publish: true,
    });
    await this.repository.publish(userId, taskId, post.id, new Date(this.now()).toISOString());
    return post;
  }

  async findPublishedPost(userId: number, taskId: number) {
    const task = await this.repository.findById(userId, taskId);
    if (!task || !task.publishedPostId) return null;
    return this.publicPosts.findPublishedPost(task.publishedPostId);
  }

  listEventsAfter(taskId: number, cursor: number, limit = 100) {
    return this.repository.listEventsAfter(taskId, cursor, limit);
  }

  private async hydrateContent(view: BlogAgentTaskView): Promise<void> {
    if (view.task.contentObjectKey) {
      if (!this.contentStorage) throw new BlogAgentTaskError(503, "博客正文 OSS 存储尚未配置");
      view.task.content = await this.contentStorage.read(view.task.contentObjectKey);
      delete view.task.contentObjectKey;
    }
    for (const version of view.versions) {
      if (version.contentObjectKey) {
        if (!this.contentStorage) throw new BlogAgentTaskError(503, "博客正文 OSS 存储尚未配置");
        version.content = await this.contentStorage.read(version.contentObjectKey);
        delete version.contentObjectKey;
      }
    }
  }

  private async readTaskContent(task: BlogAgentTask): Promise<string> {
    if (task.content !== null) return task.content;
    if (!task.contentObjectKey) return "";
    if (!this.contentStorage) throw new BlogAgentTaskError(503, "博客正文 OSS 存储尚未配置");
    return this.contentStorage.read(task.contentObjectKey);
  }
}

function excerpt(value: string, length: number): string {
  return value.length <= length ? value : `${value.slice(0, length - 1)}…`;
}
