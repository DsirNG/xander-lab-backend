import type {
  PresentationAssetPort, PresentationAttachmentPort, PresentationJob, PresentationRenderer, PresentationStorage, PresentationVersion, PresentationView,
} from "../interfaces/agent-presentation.js";
import { parsePresentationDeck, parsePresentationSlide, PresentationError } from "./agent-presentation-validation.js";

type Exclusive = <T>(callback: () => Promise<T>) => Promise<T>;
const ACTIVE = new Set(["queued", "rendering", "uploading"]);
const MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

/** One durable object owns a presentation's versions, cancellation fence and watchdog. */
export class AgentPresentationTaskService {
  constructor(private readonly storage: PresentationStorage, private readonly renderer: PresentationRenderer,
    private readonly attachments: PresentationAttachmentPort, private readonly exclusive: Exclusive = callback => callback(),
    private readonly assets?: PresentationAssetPort) {}

  async start(input: { id: string; userId: number; conversationId: number; deck: unknown }): Promise<void> {
    await this.atomic(async task => {
      const existing = await task.storage.get<PresentationJob>("job");
      if (existing) {
        if (existing.userId !== input.userId || existing.conversationId !== input.conversationId) throw new PresentationError(404, "PPT 不存在");
        const version = await task.version(existing.version);
        if (version.status === "queued") await task.storage.setAlarm(Date.now() + 1);
        return;
      }
      const deck = parsePresentationDeck(input.deck);
      // Persist the alarm first: an interrupted initial write can safely be retried.
      await task.storage.setAlarm(Date.now() + 100);
      await task.storage.put("version:1", { version: 1, attempt: 1, status: "queued", deck, createdAt: new Date().toISOString() } satisfies PresentationVersion);
      await task.storage.put("job", { id: input.id, userId: input.userId, conversationId: input.conversationId, version: 1 } satisfies PresentationJob);
    });
  }

  async view(userId: number, selected?: number): Promise<PresentationView> {
    const job = await this.owned(userId);
    const row = await this.version(selected ?? job.version);
    const history = await Promise.all(Array.from({ length: job.version }, async (_, index) => {
      const row = await this.version(index + 1);
      return { version: row.version, status: row.status };
    }));
    const { files: ids, attempt: _attempt, ...data } = row;
    let files: PresentationView["files"];
    if (ids) {
      const all = [ids.pptx, ids.source, ...ids.previews];
      const refs = [];
      for (let start = 0; start < all.length; start += 5) refs.push(...await this.attachments.resolveOwned(userId, all.slice(start, start + 5)));
      files = { pptx: refs[0]!, source: refs[1]!, previews: refs.slice(2) };
    }
    return { ...data, id: job.id, conversationId: job.conversationId, currentVersion: job.version, history, files };
  }

  async mutate(userId: number, action: string, input: Record<string, unknown>): Promise<void> {
    await this.atomic(async task => {
      const job = await task.owned(userId);
      const row = await task.version(job.version);
      const key = typeof input.requestId === "string" && /^[a-zA-Z0-9_-]{8,100}$/.test(input.requestId) ? input.requestId : null;
      if (!key) throw new PresentationError(400, "PPT 操作缺少请求标识");
      const receiptKey = `request:${key}`;
      const fingerprint = JSON.stringify({ action, input });
      const prior = await task.storage.get<string>(receiptKey);
      if (prior) {
        if (prior !== fingerprint) throw new PresentationError(409, "请求标识已被其他操作使用");
        return;
      }
      if (input.expectedVersion !== job.version) throw new PresentationError(409, "PPT 版本已更新，请刷新后操作");
      if (action === "cancel") {
        if (ACTIVE.has(row.status)) {
          await task.storage.put(`version:${row.version}`, { ...row, status: "cancelled" });
          await task.storage.deleteAlarm();
        }
      } else if (action === "retry") {
        if (!["failed", "cancelled"].includes(row.status)) throw new PresentationError(409, "只有失败或取消的 PPT 可以重试");
        if (row.attempt >= 5) throw new PresentationError(429, "此版本重试次数已达上限，请修改页面后生成新版本");
        await task.storage.setAlarm(Date.now() + 100);
        await task.storage.put(`version:${row.version}`, { ...row, attempt: row.attempt + 1, status: "queued", error: undefined });
      } else if (action === "revise") {
        if (ACTIVE.has(row.status)) throw new PresentationError(409, "请等待当前 PPT 完成或取消后修改");
        if (job.version >= 20) throw new PresentationError(400, "此 PPT 已达到 20 个版本，请新建演示文稿");
        const page = input.slideIndex;
        if (typeof page !== "number" || !Number.isInteger(page) || page < 0 || page >= row.deck.slides.length) throw new PresentationError(400, "PPT 页码无效");
        const slides = row.deck.slides.map((slide, index) => index === page ? parsePresentationSlide(input.slide) : slide);
        const deck = parsePresentationDeck({ ...row.deck, slides });
        const version = job.version + 1;
        await task.storage.setAlarm(Date.now() + 100);
        await task.storage.put(`version:${version}`, { version, attempt: 1, status: "queued", deck, createdAt: new Date().toISOString() } satisfies PresentationVersion);
        await task.storage.put("job", { ...job, version });
      } else throw new PresentationError(404, "PPT 操作不存在");
      await task.storage.put(receiptKey, fingerprint);
    });
  }

  async alarm(): Promise<void> {
    const claim = await this.atomic(async task => {
      const job = await task.storage.get<PresentationJob>("job");
      if (!job) return null;
      const row = await task.version(job.version);
      if (!ACTIVE.has(row.status)) { await task.storage.deleteAlarm(); return null; }
      if (row.status !== "queued") {
        await task.storage.put(`version:${row.version}`, { ...row, status: "failed", error: "PPT 任务执行中断，请手动重试" });
        await task.storage.deleteAlarm();
        return null;
      }
      await task.storage.setAlarm(Date.now() + 6 * 60_000);
      await task.storage.put(`version:${row.version}`, { ...row, status: "rendering" });
      return { job, row };
    });
    if (!claim) return;
    const { job, row } = claim;
    const update = (changes: Partial<PresentationVersion>) => this.atomic(async task => {
      const latest = await task.version(row.version);
      if (latest.attempt !== row.attempt || !ACTIVE.has(latest.status)) return false;
      await task.storage.put(`version:${row.version}`, { ...latest, ...changes });
      if (changes.status && !ACTIVE.has(changes.status)) await task.storage.deleteAlarm();
      return true;
    });
    try {
      const assets = await this.assets?.load(job.userId, row.deck) ?? [];
      const output = await this.renderer.render(row.deck, assets);
      if (!await update({ status: "uploading" })) return;
      const upload = async (name: string, type: string, bytes: Uint8Array) => {
        const result = await this.attachments.upload(job.userId, { name, type, bytes, size: bytes.length });
        return result.id;
      };
      const prefix = `presentation-${job.id}-v${row.version}`;
      const pptx = await upload(`${prefix}.pptx`, MIME, output.pptx);
      if (!await update({})) return;
      const source = await upload(`${prefix}.json`, "application/json", output.source);
      const previews: number[] = [];
      for (let index = 0; index < output.previews.length; index++) {
        if (!await update({})) return;
        previews.push(await upload(`${prefix}-${index + 1}.png`, "image/png", output.previews[index]!));
      }
      await update({ status: "completed", files: { pptx, source, previews }, error: undefined });
    } catch (error) {
      const message = error instanceof PresentationError
        ? error.message
        : error instanceof Error && error.message.trim()
          ? error.message
          : "PPT 渲染或保存失败";
      await update({ status: "failed", error: message });
    }
  }

  private async owned(userId: number): Promise<PresentationJob> {
    const job = await this.storage.get<PresentationJob>("job");
    if (!job || job.userId !== userId) throw new PresentationError(404, "PPT 不存在");
    return job;
  }
  private atomic<T>(callback: (task: AgentPresentationTaskService) => Promise<T>): Promise<T> {
    return this.exclusive(() => this.storage.transaction
      ? this.storage.transaction(storage => callback(new AgentPresentationTaskService(storage, this.renderer, this.attachments)))
      : callback(this));
  }
  private async version(version: number): Promise<PresentationVersion> {
    if (!Number.isInteger(version) || version < 1) throw new PresentationError(400, "PPT 版本无效");
    const row = await this.storage.get<PresentationVersion>(`version:${version}`);
    if (!row) throw new PresentationError(404, "PPT 版本不存在");
    return row;
  }
}
