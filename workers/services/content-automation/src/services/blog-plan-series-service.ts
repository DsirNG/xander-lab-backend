import type { BlogPlanRepository } from "../interfaces/blog-plan-repository.js";
import type {
  BlogPlanSeries,
  BlogPlanSeriesRepository,
  BlogPlanSeriesMode,
} from "../interfaces/blog-plan-series-repository.js";
import { BlogPlanError, BlogPlanService } from "./blog-plan-service.js";

type Input = Record<string, unknown>;
const AI_OPTIONS = new Set(["DEEP", "PRACTICAL", "NEWS", "OPINION"]);
const MODES = new Set<BlogPlanSeriesMode>(["DRAFT", "REVIEW", "AUTO"]);
const AVAILABLE_PLATFORMS = new Set(["local", "csdn", "juejin"]);
const PENDING_PLATFORMS = new Set([
  "wechat_mp",
  "weibo",
  "zhihu",
  "baijiahao",
  "cnblogs",
  "douyin",
  "xiaohongshu",
  "toutiao",
  "51cto",
  "jianshu",
  "segmentfault",
  "oschina",
  "bilibili",
  "tencent_dev",
  "aliyun_dev",
]);
const PLATFORM_CATALOG = new Set([
  ...AVAILABLE_PLATFORMS,
  ...PENDING_PLATFORMS,
]);
const text = (
  value: unknown,
  field: string,
  max: number,
  required = false,
): string => {
  if (value === undefined || value === null) value = "";
  if (
    typeof value !== "string" ||
    value.trim().length > max ||
    (required && !value.trim())
  ) {
    throw new BlogPlanError(400, `${field}格式无效`);
  }
  return value.trim();
};

/** Coordinates saved series drafts and their confirmed one-shot article schedules. */
export class BlogPlanSeriesService {
  constructor(
    private readonly repository: BlogPlanSeriesRepository,
    private readonly plans: BlogPlanService,
    private readonly planRepository: BlogPlanRepository,
    private readonly now: () => number = Date.now,
  ) {}

  async createDraft(userId: number, body: Input) {
    const clientRequestId = body.clientRequestId;
    if (
      clientRequestId !== undefined &&
      (typeof clientRequestId !== "string" ||
        !/^[\w:-]{1,128}$/.test(clientRequestId))
    ) {
      throw new BlogPlanError(400, "系列草案请求标识无效");
    }
    if (typeof clientRequestId === "string") {
      const existing = await this.repository.findByClientRequestId(
        userId,
        clientRequestId,
      );
      if (existing) return existing;
    }
    const draft = this.normalize(userId, body);
    let topics: string[];
    if (Array.isArray(body.topics)) {
      topics = this.validateTopics(body.topics, body.days);
    } else {
      topics = await this.plans.generateTopics(userId, {
        topic: draft.topic,
        count: body.days,
        audience: draft.audience,
        tone: draft.tone,
      });
      topics = this.validateTopics(topics, body.days);
    }
    const id = await this.repository.create({
      ...draft,
      topics,
      status: "DRAFT",
      userId,
      clientRequestId:
        typeof clientRequestId === "string" ? clientRequestId : null,
    });
    return this.requireSeries(userId, id);
  }

  async list(userId: number) {
    return this.repository.list(userId);
  }

  async detail(userId: number, id: number) {
    const series = await this.requireSeries(userId, id);
    return { ...series, items: await this.repository.listPlans(userId, id) };
  }

  async updateDraft(userId: number, id: number, patch: Input) {
    const current = await this.requireSeries(userId, id);
    if (current.status !== "DRAFT")
      throw new BlogPlanError(409, "只有草案可以修改");
    const merged = this.normalize(userId, { ...current, ...patch });
    const expectedDays = patch.days ?? current.topics.length;
    const topics =
      patch.topics === undefined
        ? this.validateTopics(current.topics, expectedDays)
        : this.validateTopics(patch.topics, expectedDays);
    const updated: BlogPlanSeries = { ...current, ...merged, topics };
    if (!(await this.repository.updateDraft(updated)))
      throw new BlogPlanError(409, "草案已变化，请刷新后重试");
    return this.requireSeries(userId, id);
  }

  async activate(userId: number, id: number) {
    const series = await this.requireSeries(userId, id);
    if (series.status === "ACTIVE") return this.detail(userId, id);
    if (series.status !== "DRAFT")
      throw new BlogPlanError(409, "系列草案已启用或已变化");
    const active = await this.planRepository.countActive(userId);
    const since = new Date(this.now() - 86_400_000).toISOString();
    const recent = await this.planRepository.countCreatedSince(userId, since);
    if (
      active + series.topics.length > 50 ||
      recent + series.topics.length > 50
    ) {
      throw new BlogPlanError(
        429,
        "系列文章数量超过当前计划额度，请减少篇数或清理已有计划",
      );
    }
    const inputs = this.plans.buildSeriesPlans(userId, id, series);
    if (!(await this.repository.activate(userId, id, inputs)))
      throw new BlogPlanError(409, "系列草案已被其他请求启用");
    return this.detail(userId, id);
  }

  async control(userId: number, id: number, action: unknown) {
    if (
      action !== "PAUSE" &&
      action !== "RESUME" &&
      action !== "CANCEL" &&
      action !== "RETRY_FAILED"
    ) {
      throw new BlogPlanError(400, "系列操作无效");
    }
    if (action === "RETRY_FAILED") {
      const current = await this.requireSeries(userId, id);
      if (current.failedItems === 0 && current.status === "ACTIVE")
        return this.detail(userId, id);
      if (!current.failedItems)
        throw new BlogPlanError(409, "当前系列没有可重试的失败计划");
    }
    const retryAt =
      action === "RETRY_FAILED"
        ? new Date(this.now()).toISOString()
        : undefined;
    if (!(await this.repository.control(userId, id, action, retryAt)))
      throw new BlogPlanError(409, "当前系列状态无法执行该操作");
    return this.detail(userId, id);
  }

  private async requireSeries(userId: number, id: number) {
    const series = await this.repository.find(userId, id);
    if (!series) throw new BlogPlanError(404, "系列计划不存在");
    return series;
  }

  private validateTopics(value: unknown, expected: unknown): string[] {
    const count =
      typeof expected === "number" && Number.isSafeInteger(expected)
        ? expected
        : undefined;
    if (
      !Array.isArray(value) ||
      value.length < 1 ||
      value.length > 30 ||
      (count !== undefined && value.length !== count) ||
      value.some(
        (topic) =>
          typeof topic !== "string" ||
          !topic.trim() ||
          topic.trim().length > 500,
      )
    ) {
      throw new BlogPlanError(
        400,
        "每日主题需要是 1-30 个完整主题，且数量与计划天数一致",
      );
    }
    const topics = value.map((topic) => (topic as string).trim());
    if (
      new Set(topics.map((topic) => topic.toLocaleLowerCase())).size !==
      topics.length
    ) {
      throw new BlogPlanError(400, "每日主题不能重复");
    }
    return topics;
  }

  private normalize(
    userId: number,
    body: Input,
  ): Omit<
    BlogPlanSeries,
    | "id"
    | "createdAt"
    | "updatedAt"
    | "topics"
    | "status"
    | "totalItems"
    | "finishedItems"
    | "failedItems"
  > {
    const topic = text(body.topic, "系列方向", 500, true);
    const topics = Array.isArray(body.topics) ? body.topics : [];
    const days = body.days ?? topics.length;
    if (
      typeof days !== "number" ||
      !Number.isSafeInteger(days) ||
      days < 1 ||
      days > 30
    ) {
      throw new BlogPlanError(400, "系列天数需在 1-30 之间");
    }
    const timezone = text(body.timezone ?? "Asia/Shanghai", "时区", 64, true);
    try {
      new Intl.DateTimeFormat("en", { timeZone: timezone }).format();
    } catch {
      throw new BlogPlanError(400, "时区无效");
    }
    let startDate = text(body.startDate, "开始日期", 10);
    if (!startDate) {
      const local = Object.fromEntries(
        new Intl.DateTimeFormat("en", {
          timeZone: timezone,
          year: "numeric",
          month: "2-digit",
          day: "2-digit",
        })
          .formatToParts(new Date(this.now()))
          .map(({ type, value }) => [type, value]),
      );
      const nextDay = new Date(
        Date.UTC(
          Number(local.year),
          Number(local.month) - 1,
          Number(local.day) + 1,
        ),
      );
      startDate = nextDay.toISOString().slice(0, 10);
    }
    const parsedDate = new Date(`${startDate}T00:00:00.000Z`);
    if (
      !/^\d{4}-\d{2}-\d{2}$/.test(startDate) ||
      !Number.isFinite(parsedDate.getTime()) ||
      parsedDate.toISOString().slice(0, 10) !== startDate
    ) {
      throw new BlogPlanError(400, "开始日期格式应为 YYYY-MM-DD");
    }
    const triggerTime = text(body.triggerTime ?? "20:00", "发布时间", 5, true);
    if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(triggerTime))
      throw new BlogPlanError(400, "发布时间格式应为 HH:mm");
    const aiOption = body.aiOption ?? "DEEP";
    if (typeof aiOption !== "string" || !AI_OPTIONS.has(aiOption))
      throw new BlogPlanError(400, "不支持的 AI 写作方向");
    const publishMode = body.publishMode ?? "REVIEW";
    if (
      typeof publishMode !== "string" ||
      !MODES.has(publishMode as BlogPlanSeriesMode)
    )
      throw new BlogPlanError(400, "发布模式无效");
    let requested: unknown = body.platforms ?? ["local"];
    if (
      !Array.isArray(requested) ||
      requested.some(
        (platform) =>
          typeof platform !== "string" || !PLATFORM_CATALOG.has(platform),
      )
    ) {
      throw new BlogPlanError(400, "发布平台选择无效");
    }
    const platforms = [...new Set(requested as string[])];
    if (!platforms.includes("local")) platforms.unshift("local");
    if (platforms.some((platform) => !AVAILABLE_PLATFORMS.has(platform))) {
      throw new BlogPlanError(400, "选择的平台尚未开放发布");
    }
    return {
      userId,
      title: text(
        body.title ?? `${topic} ${days}天系列`,
        "系列名称",
        120,
        true,
      ),
      topic,
      audience: text(body.audience, "目标读者", 120),
      tone: text(body.tone, "文风语气", 60),
      aiOption: aiOption as BlogPlanSeries["aiOption"],
      startDate,
      triggerTime,
      timezone,
      platforms: platforms as BlogPlanSeries["platforms"],
      publishMode: publishMode as BlogPlanSeriesMode,
    };
  }
}
