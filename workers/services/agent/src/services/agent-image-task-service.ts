import type { AgentImageTask, AgentImageTaskStorage } from "../interfaces/agent-image-task.js";
import type { AgentTurnRepository } from "../interfaces/agent-run-repository.js";
import { AgentImageError, type AgentImageService } from "./agent-image-service.js";

interface StoredTask extends AgentImageTask { started: boolean; done: boolean }
const INTERRUPTED = "图片生成任务已中断，请重试。";

/** Durable execution never repeats a provider request after an uncertain interruption. */
export class AgentImageTaskService {
  constructor(
    private readonly storage: AgentImageTaskStorage,
    private readonly repository: AgentTurnRepository,
    private readonly images: AgentImageService,
  ) {}

  async start(task: AgentImageTask): Promise<void> {
    const existing = await this.storage.get<StoredTask>("task");
    if (existing?.done) return;
    if (!existing) await this.storage.put("task", { ...task, started: false, done: false });
    // The alarm is durable before start is acknowledged to the HTTP caller.
    // Retrying start must not move a running task's watchdog forward.
    if (!existing?.started) await this.storage.setAlarm(Date.now() + 1);
  }

  async alarm(): Promise<void> {
    const task = await this.storage.get<StoredTask>("task");
    if (!task || task.done) return;
    const { userId, conversationId, runVersion } = task;
    const conversation = await this.repository.findOwnedConversation(userId, conversationId);
    if (!conversation || conversation.runVersion !== runVersion || conversation.status !== "running") {
      await this.finish(task);
      return;
    }
    if (task.started) {
      await this.repository.failTurn(userId, conversationId, runVersion, INTERRUPTED);
      await this.finish(task);
      return;
    }
    await this.storage.put("task", { ...task, started: true });
    await this.storage.setAlarm(Date.now() + 8 * 60_000);
    const invocationId = `image-${conversationId}-${runVersion}`;
    const startedAt = new Date().toISOString();
    const append = (type: string, data: unknown) => this.repository.appendEvent(conversationId, runVersion, type, data);
    const ensureActive = async () => {
      if (await this.repository.isCancellationRequested(userId, conversationId, runVersion)) {
        throw new DOMException("The operation was aborted", "AbortError");
      }
    };
    let progressQueue = Promise.resolve();
    const progress = (percent: number, stage: string) => {
      progressQueue = progressQueue.then(async () => {
        await append("tool_progress", { tool: "image_generate", invocationId, outputType: "image",
          progress: percent, estimated: percent < 100, stage, startedAt });
      });
      return progressQueue;
    };
    let timer: ReturnType<typeof setInterval> | undefined;
    try {
      if (conversation.cancelRequested) throw new AgentImageError(409, "图片生成已停止。");
      await append("tool_start", { tool: "image_generate", invocationId, phase: "start", startedAt, outputType: "image" });
      await progress(5, "generating");
      const started = Date.now();
      timer = setInterval(() => {
        void progress(Math.min(90, Math.floor(5 + 85 * (1 - Math.exp(-(Date.now() - started) / 35_000)))),
          "generating").catch(() => { clearInterval(timer); });
      }, 2_000);
      const result = await this.images.generate(userId, conversationId, runVersion, task.args, undefined, ensureActive);
      clearInterval(timer);
      await ensureActive();
      await progress(100, "completed");
      await append("tool_result", { tool: "image_generate", invocationId, result });
      await this.repository.finishTurn(userId, conversationId, runVersion, `![${result.title}](${result.url})`);
    } catch (error) {
      clearInterval(timer);
      await progressQueue.catch(() => {});
      if (await this.repository.isCancellationRequested(userId, conversationId, runVersion)) {
        await this.finish(task);
        return;
      }
      const message = error instanceof AgentImageError ? error.message : "图片生成失败，请重试。";
      await append("tool_error", { tool: "image_generate", invocationId, error: message });
      await this.repository.failTurn(userId, conversationId, runVersion, message);
    } finally {
      clearInterval(timer);
    }
    await this.finish(task);
  }

  private async finish(task: StoredTask) {
    await this.storage.put("task", { ...task, done: true });
    await this.storage.deleteAlarm();
  }
}
