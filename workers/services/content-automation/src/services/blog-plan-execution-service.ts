import type { BlogPlanExecutionPort } from "../interfaces/blog-plan-execution-port.js";
import type { BlogPlan, BlogPlanRepository, BlogPlanRun } from "../interfaces/blog-plan-repository.js";
import type { BlogPlanModelPort, BlogPlanModelResponse, BlogPlanModelStage } from "../interfaces/blog-plan-model-port.js";
import type { BlogPlanMaterialPort } from "../interfaces/blog-plan-material-port.js";
import type { BlogPostCreationService } from "./blog-post-creation-service.js";
import type { ExternalPublicationService } from "./external-publication-service.js";
import type { ContentNotificationService } from "./content-notification-service.js";
import { nextOccurrence } from "./blog-plan-service.js";

export class BlogPlanExecutionError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "BlogPlanExecutionError";
  }
}

function jsonObject(response: BlogPlanModelResponse): Record<string, unknown> {
  let payload: unknown;
  try { payload = JSON.parse(response.payloadJson); }
  catch { throw new Error("博客模型阶段结果不是有效 JSON"); }
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new Error("博客模型阶段结果格式无效");
  }
  return payload as Record<string, unknown>;
}

function safeText(value: unknown, max: number, fallback = ""): string {
  return typeof value === "string" ? value.trim().slice(0, max) : fallback;
}

function usageSum(values: BlogPlanModelResponse[]) {
  return values.reduce((sum, value) => ({
    inputTokens: sum.inputTokens + value.inputTokens,
    cachedTokens: sum.cachedTokens + value.cachedTokens,
    outputTokens: sum.outputTokens + value.outputTokens,
  }), { inputTokens: 0, cachedTokens: 0, outputTokens: 0 });
}

const AI_OPTIONS: Record<BlogPlan["aiOption"], string> = {
  DEEP: "深度解析，讲清核心原理与最佳实践",
  PRACTICAL: "实用指南，突出具体步骤和可执行建议",
  NEWS: "行业动态，区分已证实事实与趋势判断",
  OPINION: "观点分析，清晰呈现论据和适用边界",
};

export class BlogPlanExecutionService implements BlogPlanExecutionPort {
  constructor(
    private readonly repository: BlogPlanRepository,
    private readonly model: BlogPlanModelPort,
    private readonly posts: BlogPostCreationService,
    private readonly publications: ExternalPublicationService,
    private readonly notifications: ContentNotificationService,
    private readonly materials?: BlogPlanMaterialPort,
    private readonly now: () => number = Date.now,
  ) {}

  async trigger(userId: number, planId: number): Promise<BlogPlanRun> {
    const plan = await this.repository.findById(userId, planId);
    if (!plan) throw new BlogPlanExecutionError(404, "定时发文计划不存在");
    if (plan.status === "RUNNING") throw new BlogPlanExecutionError(409, "计划正在执行，请稍后重试");
    if (plan.runOnce) throw new BlogPlanExecutionError(409, "一次性计划不支持手动触发");
    const now = new Date(this.now()).toISOString();
    const runId = await this.repository.createManualRun(userId, planId, now, crypto.randomUUID());
    if (!runId) throw new BlogPlanExecutionError(409, "计划正在执行，请稍后重试");
    const run = await this.repository.findRun(planId, runId);
    if (!run) throw new Error("Manual blog-plan run disappeared after creation");
    return run;
  }

  async executeManual(userId: number, planId: number, runId: number): Promise<void> {
    const plan = await this.repository.findById(userId, planId);
    const run = await this.repository.findRun(planId, runId);
    if (!plan || !run || run.triggerType !== "MANUAL") return;
    await this.execute(plan, run, null);
  }

  async processDue(now: number): Promise<void> {
    const nowText = new Date(now).toISOString();
    await this.recoverStaleClaims(now);
    const claimed = await this.repository.claimDue(nowText, 5, crypto.randomUUID());
    await Promise.allSettled(claimed.map(async (plan) => {
      const token = plan.processingToken;
      const scheduledAt = plan.nextRunAt ?? nowText;
      if (!token) return;
      let runId: number | null;
      try {
        runId = await this.repository.createScheduledRun(plan, scheduledAt, token);
      } catch (error) {
        const message = this.errorMessage(error);
        await this.repository.finishScheduledPlan({
          planId: plan.id, token, status: plan.runOnce ? "FAILED" : "ACTIVE",
          nextRunAt: plan.runOnce ? null : new Date(nextOccurrence(plan, now)).toISOString(),
          lastRunAt: scheduledAt, errorMessage: message, updatedAt: nowText,
        });
        return;
      }
      if (runId === null) {
        const nextRunAt = plan.runOnce ? null : new Date(nextOccurrence(plan, now)).toISOString();
        await this.repository.finishScheduledPlan({
          planId: plan.id, token, status: plan.runOnce ? "FAILED" : "ACTIVE",
          nextRunAt, lastRunAt: scheduledAt, errorMessage: "同一次触发已存在执行记录，已跳过",
          updatedAt: nowText,
        });
        return;
      }
      const run = await this.repository.findRun(plan.id, runId);
      if (run) await this.execute(plan, run, token);
      else {
        await this.repository.finishScheduledPlan({
          planId: plan.id, token, status: plan.runOnce ? "FAILED" : "ACTIVE",
          nextRunAt: plan.runOnce ? null : new Date(nextOccurrence(plan, now)).toISOString(),
          lastRunAt: scheduledAt, errorMessage: "计划执行记录不可读取", updatedAt: nowText,
        });
      }
    }));
  }

  private async recoverStaleClaims(now: number): Promise<void> {
    if (!this.repository.listStaleClaims || !this.repository.recoverStalePlan) return;
    const cutoff = new Date(now - 30 * 60_000).toISOString();
    const updatedAt = new Date(now).toISOString();
    const stalePlans = await this.repository.listStaleClaims(cutoff, 5);
    for (const plan of stalePlans) {
      const token = plan.processingToken;
      if (!token) continue;
      const succeeded = plan.runOnce && this.repository.hasSucceededRun
        ? await this.repository.hasSucceededRun(plan.id, token) : false;
      const terminalError = succeeded ? null : "执行进程意外中断，本次触发未完成";
      const nextRunAt = plan.runOnce ? null : new Date(nextOccurrence(plan, now)).toISOString();
      await this.repository.recoverStalePlan({
        planId: plan.id,
        token,
        cutoff,
        status: plan.runOnce ? (succeeded ? "FINISHED" : "FAILED") : "ACTIVE",
        nextRunAt,
        lastRunAt: plan.nextRunAt ?? updatedAt,
        errorMessage: terminalError,
        updatedAt,
      });
    }
    await this.repository.failStaleManualRuns?.(cutoff, updatedAt, 20);
  }

  private async execute(plan: BlogPlan, run: BlogPlanRun, scheduledToken: string | null): Promise<void> {
    const completedAt = () => new Date(this.now()).toISOString();
    let stage = "generate";
    let terminal: "SUCCEEDED" | "FAILED" | "REVIEW_REJECTED" = "FAILED";
    let terminalMessage: string | null = null;
    const calls: BlogPlanModelResponse[] = [];
    try {
      await this.repository.updateRun(plan.id, run.id, {
        status: "GENERATING", stageError: null, errorMessage: null,
        startedAt: run.startedAt ?? completedAt(),
      });
      await this.model.ensureAvailable(plan.userId);
      const queuedTopic = await this.repository.consumeNextTopic(plan.id);
      const input = await this.buildInput(plan, queuedTopic ?? plan.topic);

      const analysisCall = await this.model.runStage("analyze", { input });
      calls.push(analysisCall);
      const analysis = jsonObject(analysisCall);
      const researchCall = await this.model.runStage("research", {
        input, analysisJson: JSON.stringify(analysis),
      });
      calls.push(researchCall);
      const research = jsonObject(researchCall);
      const articleCall = await this.model.runStage("article", {
        input: `${input}\n\n策划结果：\n${JSON.stringify(analysis)}\n\n调研结果：\n${JSON.stringify(research)}`,
      });
      calls.push(articleCall);
      const article = jsonObject(articleCall);
      let content = safeText(article.content, 1_800_000);
      const title = safeText(article.title, 300);
      if (!title || !content) throw new Error("博客模型没有生成可发布的标题和正文");

      stage = "review";
      await this.repository.updateRun(plan.id, run.id, { status: "REVIEWING" });
      const reviewCall = await this.model.runStage("review", {
        request: "检查计划生成文章的事实、逻辑、结构和 Markdown。",
        content,
      });
      calls.push(reviewCall);
      const reviewed = jsonObject(reviewCall);
      content = safeText(reviewed.content, 1_800_000, content) || content;
      const complianceCall = await this.model.runStage("compliance", { title, content });
      calls.push(complianceCall);
      const compliance = jsonObject(complianceCall);
      if (compliance.pass !== true) {
        const reasons = Array.isArray(compliance.reasons)
          ? compliance.reasons.filter((item): item is string => typeof item === "string").map((item) => item.slice(0, 500))
          : [];
        terminalMessage = (reasons.join("；") || "文章未通过内容安全审核").slice(0, 1000);
        terminal = "REVIEW_REJECTED";
        await this.repository.updateRun(plan.id, run.id, {
          status: terminal, reviewPass: false, reviewReason: terminalMessage,
          finishedAt: completedAt(),
        });
        await this.notify(plan, run, "REVIEW_REJECTED", "文章未通过合规审核", terminalMessage);
        return;
      }

      stage = "publish";
      await this.repository.updateRun(plan.id, run.id, { status: "PUBLISHING", reviewPass: true });
      const categoryId = ["frontend", "backend", "architecture", "devops", "career"].includes(String(article.categoryId))
        ? String(article.categoryId) : "backend";
      const tags = Array.isArray(article.tags)
        ? article.tags.filter((item): item is string => typeof item === "string").map((item) => item.slice(0, 100)).slice(0, 8)
        : [];
      await this.model.chargeTokens({
        userId: plan.userId, runId: run.id, planId: plan.id,
        usage: usageSum(calls), model: calls.at(-1)?.model ?? "unknown",
      });
      const post = await this.posts.create({
        userId: plan.userId,
        idempotencyKey: `blog-plan-run-${run.id}`,
        title,
        summary: safeText(article.summary, 2_000),
        content,
        categoryId,
        coverImage: safeText(article.coverImage, 2_000) || null,
        tags,
        publish: plan.autoPublish,
      });
      await this.repository.updateRun(plan.id, run.id, { localPostId: post.id });

      if (!plan.autoPublish && plan.seriesPublishMode === "REVIEW") {
        await this.notify(plan, run, "DRAFT_READY", "系列文章草稿已生成",
          `第 ${plan.seriesDay ?? ""} 天的文章《${title}》已生成草稿，请检查后再发布。`);
      }

      if (plan.autoPublish && plan.syncCsdn) {
        try {
          const result = await this.publications.sync(plan.userId, post.id, "csdn");
          await this.repository.updateRun(plan.id, run.id, {
            csdnStatus: "SUCCEEDED", csdnExternalId: result.externalId, csdnUrl: result.url,
          });
        } catch (error) {
          const message = this.errorMessage(error);
          await this.repository.updateRun(plan.id, run.id, {
            csdnStatus: "FAILED", csdnErrorCode: this.errorCode(error), csdnErrorMessage: message,
          });
          await this.notify(plan, run, "CSDN_FAILED", "CSDN 同步失败", `文章已在本站发布，但同步 CSDN 失败：${message}`);
        }
      }
      if (plan.autoPublish && plan.syncJuejin) {
        try {
          const result = await this.publications.sync(plan.userId, post.id, "juejin");
          await this.repository.updateRun(plan.id, run.id, {
            juejinStatus: "SUCCEEDED", juejinExternalId: result.externalId, juejinUrl: result.url,
          });
        } catch (error) {
          const message = this.errorMessage(error);
          await this.repository.updateRun(plan.id, run.id, {
            juejinStatus: "FAILED", juejinErrorCode: this.errorCode(error), juejinErrorMessage: message,
          });
          await this.notify(plan, run, "JUEJIN_FAILED", "掘金同步失败", `文章已在本站发布，但同步掘金失败：${message}`);
        }
      }
      terminal = "SUCCEEDED";
      await this.repository.updateRun(plan.id, run.id, { status: terminal, reviewPass: true, finishedAt: completedAt() });
    } catch (error) {
      terminalMessage = this.errorMessage(error);
      await this.repository.updateRun(plan.id, run.id, {
        status: "FAILED", stageError: stage, errorMessage: terminalMessage, finishedAt: completedAt(),
      });
      await this.notify(plan, run, "RUN_FAILED", "定时发文执行失败", `计划《${plan.topic}》本次执行失败：${terminalMessage}`);
    } finally {
      if (scheduledToken) {
        const finished = this.now();
        const nextRunAt = plan.runOnce ? null : new Date(nextOccurrence(plan, finished)).toISOString();
        await this.repository.finishScheduledPlan({
          planId: plan.id,
          token: scheduledToken,
          status: plan.runOnce ? (terminal === "SUCCEEDED" ? "FINISHED" : "FAILED") : "ACTIVE",
          nextRunAt,
          lastRunAt: run.scheduledAt,
          errorMessage: terminal === "SUCCEEDED" ? null : terminalMessage ?? "计划执行失败",
          updatedAt: completedAt(),
        });
        if (plan.planSeriesId) await this.repository.refreshSeriesStatus?.(plan.planSeriesId);
      }
    }
  }

  private async buildInput(plan: BlogPlan, topic: string): Promise<string> {
    const parts = [topic];
    if (plan.audience) parts.push(`目标读者：${plan.audience}`);
    if (plan.tone) parts.push(`文风语气：${plan.tone}`);
    parts.push(`内容规划方向：${AI_OPTIONS[plan.aiOption]}`);
    if (plan.knowledgeMaterialId !== null) {
      if (!this.materials) throw new Error("计划引用的知识材料读取服务尚未配置");
      const material = await this.materials.getOwned(plan.userId, plan.knowledgeMaterialId);
      if (!material) throw new Error("计划引用的知识材料不存在或无法读取");
      parts.push(`请优先依据以下个人知识材料进行创作：\n${material.title}\n${material.content.slice(0, 8_000)}`);
    }
    return parts.join("\n");
  }

  private async notify(plan: BlogPlan, run: BlogPlanRun, type: string, title: string, message: string): Promise<void> {
    try {
      await this.notifications.create({
        userId: plan.userId, type, title, message: message.slice(0, 1_000), planId: plan.id, runId: run.id,
      });
    } catch { /* A notification failure does not change the content-run result. */ }
  }

  private errorMessage(error: unknown): string {
    const message = error instanceof Error ? error.message : "计划执行失败";
    return message.slice(0, 1_000) || "计划执行失败";
  }

  private errorCode(error: unknown): string {
    const code = error && typeof error === "object" ? (error as Record<string, unknown>).code : null;
    return typeof code === "string" ? code.slice(0, 64) : "PLATFORM_PUBLICATION_FAILED";
  }
}
