import { AgentContentContractError, type AgentContentQueryResult, type AgentContentToolsPort } from "../interfaces/agent-content-tools-port.js";

export class AgentContentToolsError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

const MUTATION_FIELDS: Record<string, readonly string[]> = {
  schedule_plan: ["topic", "topics", "triggerTimes", "triggerTime", "timezone", "audience", "tone", "syncCsdn", "syncJuejin"],
  update_plan: ["planId", "action", "topic", "topics", "triggerTimes", "triggerTime", "timezone", "audience", "tone", "syncCsdn", "syncJuejin"],
  create_plan_series: ["title", "topic", "days", "topics", "startDate", "triggerTime", "timezone", "audience", "tone", "platforms", "publishMode", "aiOption"],
  update_plan_series: ["seriesId", "title", "topic", "days", "topics", "startDate", "triggerTime", "timezone", "audience", "tone", "platforms", "publishMode", "aiOption"],
  activate_plan_series: ["seriesId"],
  retry_plan_series: ["seriesId"],
  email_reminder: ["recipientEmail", "subject", "message", "frequency", "scheduledAt", "sendTime", "timezone", "recurrenceDay", "intervalDays", "contentType", "templateId"],
  update_email_reminder: ["taskId", "action"],
};

function safeUser(userId: number): void {
  if (!Number.isSafeInteger(userId) || userId < 1) throw new AgentContentToolsError(401, "未登录或登录已过期");
}

function argsObject(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return value as Record<string, unknown>;
}

export class AgentContentToolsService {
  constructor(private readonly content: AgentContentToolsPort) {}

  static validateMutationArguments(toolName: string, input: unknown): Record<string, unknown> {
    const args = argsObject(input);
    if (!MUTATION_FIELDS[toolName]) throw new AgentContentToolsError(400, "内容操作工具不存在");
    validateMutation(toolName, args);
    return args;
  }

  static needsApproval(toolName: string, input: unknown): boolean {
    if (toolName === "schedule_plan" || toolName === "email_reminder") return true;
    const args = argsObject(input);
    const action = typeof args.action === "string" ? args.action.trim().toUpperCase() : "";
    return toolName === "activate_plan_series" || toolName === "retry_plan_series"
      || (toolName === "update_plan" && ["CANCEL", "CANCELLED", "DELETE"].includes(action))
      || (toolName === "update_email_reminder" && action === "DELETE");
  }

  async execute(userId: number, toolName: string, input: unknown): Promise<AgentContentQueryResult> {
    return this.executeMutationOrQuery(userId, toolName, input, "", false);
  }

  async executeApprovedMutation(
    userId: number,
    toolName: string,
    input: unknown,
    invocationId: string,
  ): Promise<AgentContentQueryResult> {
    return this.executeMutationOrQuery(userId, toolName, input, invocationId, true);
  }

  private async executeMutationOrQuery(
    userId: number,
    toolName: string,
    input: unknown,
    invocationId: string,
    approved: boolean,
  ): Promise<AgentContentQueryResult> {
    safeUser(userId);
    const args = argsObject(input);
    try {
      switch (toolName) {
        case "query_posts": return await this.content.queryPosts(userId, args);
        case "query_plans": return await this.content.queryPlans(userId, args);
        case "query_plan_series": return await this.content.queryPlanSeries(userId, args);
        case "query_email_reminders": return await this.content.queryEmailReminders(userId, args);
        case "query_unsynced_posts": return await this.content.queryUnsyncedPosts(userId, args);
        case "schedule_plan":
        case "email_reminder":
        case "update_plan":
        case "create_plan_series":
        case "update_plan_series":
        case "activate_plan_series":
        case "retry_plan_series":
        case "update_email_reminder": {
          if (AgentContentToolsService.needsApproval(toolName, args) && !approved) {
            throw new AgentContentToolsError(409, "此内容操作需要用户审批");
          }
          AgentContentToolsService.validateMutationArguments(toolName, args);
          if (toolName === "schedule_plan") {
            if (!invocationId) throw new AgentContentToolsError(400, "计划请求缺少幂等标识");
            return await this.content.createPlan(userId, args, invocationId);
          }
          if (toolName === "create_plan_series") {
            return await this.content.createPlanSeries(userId, args, invocationId || crypto.randomUUID());
          }
          if (toolName === "update_plan_series") return await this.content.updatePlanSeries(userId, args);
          if (toolName === "activate_plan_series") return await this.content.activatePlanSeries(userId, args);
          if (toolName === "retry_plan_series") return await this.content.retryPlanSeries(userId, args);
          if (toolName === "email_reminder") {
            if (!invocationId) throw new AgentContentToolsError(400, "邮件提醒请求缺少幂等标识");
            return await this.content.createEmailReminder(userId, args, invocationId);
          }
          if (toolName === "update_plan") return await this.content.updatePlan(userId, args);
          return await this.content.updateEmailReminder(userId, args);
        }
        default: throw new AgentContentToolsError(400, "内容查询工具不存在");
      }
    } catch (error) {
      if (error instanceof AgentContentToolsError) throw error;
      if (error instanceof AgentContentContractError) throw new AgentContentToolsError(error.status, error.message);
      throw new AgentContentToolsError(503, "内容查询服务暂不可用");
    }
  }
}

function validateMutation(toolName: string, args: Record<string, unknown>): void {
  const allowed = MUTATION_FIELDS[toolName];
  if (!allowed || Object.keys(args).some((key) => !allowed.includes(key))) {
    throw new AgentContentToolsError(400, "内容操作参数包含不支持的字段");
  }
  if (toolName === "schedule_plan" && (typeof args.topic !== "string" || !args.topic.trim() || args.topic.length > 120)) {
    throw new AgentContentToolsError(400, "文章主题不能为空且不能超过 120 个字符");
  }
  if (toolName === "email_reminder" && ["recipientEmail", "subject", "message"].some((key) =>
    typeof args[key] !== "string" || !(args[key] as string).trim())) {
    throw new AgentContentToolsError(400, "定时邮件提醒缺少收件邮箱、主题或正文");
  }
  if (toolName === "update_plan" && (!Number.isSafeInteger(args.planId) || (args.planId as number) < 1
    || typeof args.action !== "string")) throw new AgentContentToolsError(400, "计划操作参数无效");
  if (toolName === "create_plan_series" && (typeof args.topic !== "string" || !args.topic.trim()
    || typeof args.days !== "number" || !Number.isSafeInteger(args.days) || args.days < 1 || args.days > 30
    || args.startDate !== undefined && (typeof args.startDate !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(args.startDate)))) {
    throw new AgentContentToolsError(400, "系列草案需要主题和 1-30 天；开始日期应为 YYYY-MM-DD");
  }
  if (["update_plan_series", "activate_plan_series", "retry_plan_series"].includes(toolName)
    && (!Number.isSafeInteger(args.seriesId) || (args.seriesId as number) < 1)) {
    throw new AgentContentToolsError(400, "系列计划 ID 无效");
  }
  if (["create_plan_series", "update_plan_series"].includes(toolName)
    && args.topics !== undefined && (!Array.isArray(args.topics) || args.topics.length < 1 || args.topics.length > 30
      || args.topics.some((topic) => typeof topic !== "string" || !topic.trim() || topic.length > 500))) {
    throw new AgentContentToolsError(400, "每日主题需要是 1-30 个非空主题");
  }
  if (toolName === "update_email_reminder" && (!Number.isSafeInteger(args.taskId) || (args.taskId as number) < 1
    || typeof args.action !== "string")) throw new AgentContentToolsError(400, "邮件提醒操作参数无效");
}
