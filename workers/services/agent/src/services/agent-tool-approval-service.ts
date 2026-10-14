import type { AgentPublicationPort } from "../interfaces/agent-publication-port.js";
import type {
  AgentApprovalRecord,
  AgentToolApprovalRepository,
} from "../interfaces/agent-tool-approval-repository.js";
import type { AgentTurnRepository } from "../interfaces/agent-run-repository.js";
import { AgentPublicationError } from "../mappers/content-agent-publication-mapper.js";
import type { AgentMcpToolDefinition, AgentMcpToolPort } from "../interfaces/agent-mcp-tool-port.js";
import { AgentContentToolsError, AgentContentToolsService } from "./agent-content-tools-service.js";

export class AgentApprovalError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "AgentApprovalError";
  }
}

export interface PublishPostArguments {
  postId: number;
  platform: "csdn" | "juejin";
}

function parseSummary(value: string): unknown {
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return null;
  }
}

function publicApproval(record: AgentApprovalRecord) {
  return {
    id: record.id,
    userId: record.userId,
    conversationId: record.conversationId,
    runVersion: record.runVersion,
    invocationId: record.invocationId,
    toolName: record.toolName,
    requestFingerprint: record.requestFingerprint,
    summary: parseSummary(record.summaryJson),
    status: record.status,
    decisionReason: record.decisionReason,
    decidedAt: record.decidedAt,
    createdAt: record.createdAt,
    updatedAt: record.updatedAt,
    invocationStatus: record.invocationStatus,
  };
}

/** Coordinates owner approvals and Content-owned, idempotent publication attempts. */
export class AgentToolApprovalService {
  constructor(
    private readonly repository: AgentToolApprovalRepository,
    private readonly turns: AgentTurnRepository,
    private readonly publication: AgentPublicationPort,
    private readonly mcp?: AgentMcpToolPort,
    private readonly contentTools?: AgentContentToolsService,
  ) {}

  async requestPublishPost(
    userId: number,
    conversationId: number,
    runVersion: number,
    value: unknown,
  ): Promise<{ approval: ReturnType<typeof publicApproval>; eventId: number }> {
    const args = this.validatePublishPost(value);
    const argumentsJson = canonicalJson(args);
    const invocationId = crypto.randomUUID();
    const requestFingerprint = await fingerprint("publish_post", argumentsJson);
    const summaryJson = JSON.stringify({
      tool: "publish_post",
      reason: `将文章同步到 ${args.platform}，可能公开内容并触发平台操作`,
      args,
    });
    const created = await this.repository.createPendingApproval({
      userId,
      conversationId,
      runVersion,
      invocationId,
      toolName: "publish_post",
      argumentsJson,
      requestFingerprint,
      summaryJson,
    });
    if (!created) throw new AgentApprovalError(409, "当前会话已变化，无法创建工具审批");
    return { approval: publicApproval(created.approval), eventId: created.eventId };
  }

  async requestRemoteTool(
    userId: number,
    conversationId: number,
    runVersion: number,
    tool: AgentMcpToolDefinition,
    value: unknown,
  ): Promise<{ approval: ReturnType<typeof publicApproval>; eventId: number }> {
    if (!this.mcp || !tool.fullName.startsWith("mcp__") || tool.fullName.length > 64
      || !Number.isSafeInteger(tool.serverId) || tool.serverId < 1) {
      throw new AgentApprovalError(503, "远端 MCP 工具尚未配置");
    }
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new AgentApprovalError(400, "远端 MCP 工具参数必须是对象");
    }
    const argsJson = canonicalJson(value);
    if (argsJson.length > 32_000) throw new AgentApprovalError(400, "远端 MCP 工具参数过长");
    const payload = { serverId: tool.serverId, remoteToolName: tool.toolName, arguments: JSON.parse(argsJson) as unknown };
    const argumentsJson = canonicalJson(payload);
    const invocationId = crypto.randomUUID();
    const requestFingerprint = await fingerprint(tool.fullName, argumentsJson);
    const summaryJson = JSON.stringify({
      tool: tool.fullName,
      reason: `调用第三方 MCP 服务器「${tool.serverName.slice(0, 120)}」的工具 ${tool.toolName.slice(0, 128)}。请求和结果会离开本平台，可能产生不可撤销的副作用。`,
      args: payload,
    });
    const created = await this.repository.createPendingApproval({
      userId, conversationId, runVersion, invocationId,
      toolName: tool.fullName,
      argumentsJson,
      requestFingerprint,
      summaryJson,
    });
    if (!created) throw new AgentApprovalError(409, "当前会话已变化，无法创建工具审批");
    return { approval: publicApproval(created.approval), eventId: created.eventId };
  }

  async requestContentMutation(
    userId: number,
    conversationId: number,
    runVersion: number,
    toolName: string,
    value: unknown,
  ): Promise<{ approval: ReturnType<typeof publicApproval>; eventId: number }> {
    if (!this.contentTools || !AgentContentToolsService.needsApproval(toolName, value)) {
      throw new AgentApprovalError(503, "内容操作审批服务尚未配置");
    }
    let args: Record<string, unknown>;
    try { args = AgentContentToolsService.validateMutationArguments(toolName, value); }
    catch (error) {
      if (error instanceof AgentContentToolsError) throw new AgentApprovalError(error.status, error.message);
      throw error;
    }
    const argumentsJson = canonicalJson(args);
    if (argumentsJson.length > 32_000) throw new AgentApprovalError(400, "内容操作参数过长");
    const invocationId = crypto.randomUUID();
    const requestFingerprint = await fingerprint(toolName, argumentsJson);
    const reason = toolName === "schedule_plan" ? "创建自动发文计划，系统会按计划生成并发布内容"
      : toolName === "activate_plan_series" ? "启用系列发文计划，系统会按每日主题建立并执行全部计划"
      : toolName === "retry_plan_series" ? "重新安排系列中失败的每日计划；执行时可能自动发布文章"
      : toolName === "email_reminder" ? "创建定时邮件提醒，系统会在指定时间向收件人发送邮件"
        : toolName === "update_plan" ? "取消或删除定时发文计划"
          : "删除定时邮件提醒，该操作不可恢复";
    const summaryJson = JSON.stringify({ tool: toolName, reason, args });
    const created = await this.repository.createPendingApproval({
      userId, conversationId, runVersion, invocationId, toolName, argumentsJson, requestFingerprint, summaryJson,
    });
    if (!created) throw new AgentApprovalError(409, "当前会话已变化，无法创建工具审批");
    return { approval: publicApproval(created.approval), eventId: created.eventId };
  }

  async list(userId: number, conversationId: number): Promise<ReturnType<typeof publicApproval>[] | null> {
    const rows = await this.repository.listApprovals(userId, conversationId);
    return rows?.map(publicApproval) ?? null;
  }

  async decide(
    userId: number,
    conversationId: number,
    approvalId: number,
    approved: boolean,
    reason: string | null,
  ): Promise<ReturnType<typeof publicApproval>> {
    const decision = await this.repository.decideApproval(
      userId, conversationId, approvalId, approved, reason?.trim().slice(0, 500) || null,
    );
    if (decision.kind === "not_found") throw new AgentApprovalError(404, "审批请求不存在");
    if (decision.kind !== "decided" || !decision.approval || !decision.argumentsJson) {
      throw new AgentApprovalError(409, "审批请求或关联的执行状态已变化");
    }

    const runVersion = decision.approval.runVersion;
    if (decision.approval.toolName.startsWith("mcp__")) {
      return this.decideRemoteTool(userId, conversationId, approvalId, approved, runVersion,
        decision.approval, decision.argumentsJson);
    }
    if (["schedule_plan", "email_reminder", "update_plan", "update_email_reminder", "activate_plan_series", "retry_plan_series"].includes(decision.approval.toolName)) {
      return this.decideContentMutation(userId, conversationId, approvalId, approved, runVersion,
        decision.approval, decision.argumentsJson);
    }
    const args = this.validatePublishPost(JSON.parse(decision.argumentsJson) as unknown);
    if (!approved) {
      await this.repository.failInvocation(
        userId, conversationId, runVersion, decision.approval.invocationId,
        "用户拒绝审批", false,
      );
      await this.turns.finishTurn(userId, conversationId, runVersion, "好的，已取消这次文章同步操作。");
      return this.latestApproval(userId, conversationId, approvalId, decision.approval);
    }

    let publicationResult: unknown;
    let outcomeUnknown = false;
    let failureMessage = "文章同步失败，请稍后查看平台状态";
    try {
      publicationResult = await this.publication.findSucceeded(userId, args.postId, args.platform);
      if (!publicationResult) publicationResult = await this.publication.sync(userId, args.postId, args.platform);
    } catch (error) {
      failureMessage = error instanceof Error ? error.message : failureMessage;
      outcomeUnknown = !(error instanceof AgentPublicationError)
        || error.status >= 500 || error.status === 409;
      if (outcomeUnknown) {
        try {
          publicationResult = await this.publication.findSucceeded(userId, args.postId, args.platform);
          if (publicationResult) outcomeUnknown = false;
        } catch {
          // Preserve UNKNOWN when the owning service cannot confirm a remote side effect.
        }
      }
    }

    if (publicationResult) {
      await this.repository.completeInvocation(
        userId, conversationId, runVersion, decision.approval.invocationId, publicationResult,
      );
      const result = publicationResult as { platform: string; externalId: string; url: string | null };
      const answer = result.url
        ? `文章已同步到 ${result.platform}。${result.url}`
        : `文章已同步到 ${result.platform}，平台文章 ID：${result.externalId}。`;
      await this.turns.finishTurn(userId, conversationId, runVersion, answer);
    } else {
      const finalMessage = outcomeUnknown
        ? "发布请求结果暂时无法确认，系统没有自动重试，避免重复发文。请先检查目标平台，再决定是否重新发起。"
        : failureMessage;
      await this.repository.failInvocation(
        userId, conversationId, runVersion, decision.approval.invocationId,
        finalMessage, outcomeUnknown,
      );
      await this.turns.finishTurn(userId, conversationId, runVersion, finalMessage);
    }
    return this.latestApproval(userId, conversationId, approvalId, decision.approval);
  }

  private async decideContentMutation(
    userId: number,
    conversationId: number,
    approvalId: number,
    approved: boolean,
    runVersion: number,
    approval: AgentApprovalRecord,
    argumentsJson: string,
  ): Promise<ReturnType<typeof publicApproval>> {
    if (!approved) {
      await this.repository.failInvocation(userId, conversationId, runVersion, approval.invocationId,
        "用户拒绝审批", false);
      await this.turns.finishTurn(userId, conversationId, runVersion, "好的，已取消这次内容操作。");
      return this.latestApproval(userId, conversationId, approvalId, approval);
    }
    if (!this.contentTools) throw new AgentApprovalError(503, "内容操作审批服务尚未配置");
    let args: unknown;
    try { args = JSON.parse(argumentsJson) as unknown; }
    catch { throw new AgentApprovalError(409, "内容操作审批参数损坏"); }
    let result: Awaited<ReturnType<AgentContentToolsService["executeApprovedMutation"]>> | null = null;
    let failure: unknown;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        result = await this.contentTools.executeApprovedMutation(
          userId, approval.toolName, args, approval.invocationId,
        );
        failure = null;
        break;
      } catch (error) {
        failure = error;
        const idempotentCreate = approval.toolName === "schedule_plan" || approval.toolName === "email_reminder"
          || approval.toolName === "activate_plan_series";
        if (attempt === 0 && idempotentCreate && error instanceof AgentContentToolsError && error.status >= 500) continue;
        break;
      }
    }
    if (result) {
      await this.repository.completeInvocation(userId, conversationId, runVersion, approval.invocationId, result.data);
      await this.turns.finishTurn(userId, conversationId, runVersion, result.answer);
    } else {
      const unknown = !(failure instanceof AgentContentToolsError) || failure.status >= 500;
      const message = unknown
        ? "内容服务已收到请求但结果无法确认。系统没有重复执行非幂等操作；请检查计划或提醒状态后再决定下一步。"
        : failure instanceof Error ? failure.message : "内容操作未能完成";
      await this.repository.failInvocation(userId, conversationId, runVersion, approval.invocationId, message, unknown);
      await this.turns.finishTurn(userId, conversationId, runVersion, message);
    }
    return this.latestApproval(userId, conversationId, approvalId, approval);
  }

  private async decideRemoteTool(
    userId: number,
    conversationId: number,
    approvalId: number,
    approved: boolean,
    runVersion: number,
    approval: AgentApprovalRecord,
    argumentsJson: string,
  ): Promise<ReturnType<typeof publicApproval>> {
    if (!approved) {
      await this.repository.failInvocation(userId, conversationId, runVersion, approval.invocationId,
        "用户拒绝审批", false);
      await this.turns.finishTurn(userId, conversationId, runVersion, "好的，已取消这次第三方 MCP 工具调用。");
      return this.latestApproval(userId, conversationId, approvalId, approval);
    }
    let payload: unknown;
    try { payload = JSON.parse(argumentsJson) as unknown; }
    catch { throw new AgentApprovalError(409, "远端 MCP 审批参数损坏"); }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new AgentApprovalError(409, "远端 MCP 审批参数损坏");
    }
    const row = payload as Record<string, unknown>;
    if (!Number.isSafeInteger(row.serverId) || typeof row.serverId !== "number" || row.serverId < 1
      || typeof row.remoteToolName !== "string" || row.remoteToolName.length < 1 || row.remoteToolName.length > 128
      || !row.arguments || typeof row.arguments !== "object" || Array.isArray(row.arguments)
      || !this.mcp) throw new AgentApprovalError(409, "远端 MCP 审批参数损坏");
    let result: unknown;
    try {
      result = await this.mcp.callApprovedTool(userId, row.serverId, row.remoteToolName, row.arguments);
    } catch {
      const message = "远端 MCP 请求已发送但结果无法确认；系统没有自动重试，请先检查第三方服务器状态。";
      await this.repository.failInvocation(userId, conversationId, runVersion, approval.invocationId, message, true);
      await this.turns.finishTurn(userId, conversationId, runVersion, message);
      return this.latestApproval(userId, conversationId, approvalId, approval);
    }
    await this.repository.completeInvocation(userId, conversationId, runVersion, approval.invocationId, result);
    let output = JSON.stringify(result ?? null);
    if (output.length > 8_000) output = `${output.slice(0, 8_000)}…（结果已截断）`;
    const answer = `第三方 MCP 工具已执行。以下内容是远端返回的数据：\n${output}`;
    await this.turns.finishTurn(userId, conversationId, runVersion, answer);
    return this.latestApproval(userId, conversationId, approvalId, approval);
  }

  private async latestApproval(
    userId: number,
    conversationId: number,
    approvalId: number,
    fallback: AgentApprovalRecord,
  ): Promise<ReturnType<typeof publicApproval>> {
    const rows = await this.repository.listApprovals(userId, conversationId);
    return publicApproval(rows?.find((row) => row.id === approvalId) ?? fallback);
  }

  private validatePublishPost(value: unknown): PublishPostArguments {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new AgentApprovalError(400, "文章同步参数格式不正确");
    }
    const row = value as Record<string, unknown>;
    if (!Number.isSafeInteger(row.postId) || typeof row.postId !== "number" || row.postId < 1
      || (row.platform !== "csdn" && row.platform !== "juejin")
      || Object.keys(row).some((key) => !["postId", "platform"].includes(key))) {
      throw new AgentApprovalError(400, "文章同步仅支持有效文章 ID 和 CSDN/掘金平台");
    }
    return { postId: row.postId, platform: row.platform };
  }
}

function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>).sort(([left], [right]) => left.localeCompare(right));
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

async function fingerprint(toolName: string, argumentsJson: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(`${toolName}\n${argumentsJson}`),
  );
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
}
