import type { AgentToolDefinition } from "./agent-tool-registry.js";

export interface AgentToolContext {
  userId: number;
  conversationId: number;
  runVersion: number;
  invocationId: string;
  idempotencyKey: string;
  signal: AbortSignal;
}

export interface AgentToolResult {
  ok: boolean;
  data?: unknown;
  answer?: string;
  artifacts?: unknown[];
  links?: unknown[];
  retryable?: boolean;
  errorCode?: string;
}

export interface AgentTool {
  definition(): AgentToolDefinition & { readOnly?: boolean };
  execute(context: AgentToolContext, args: Record<string, unknown>): Promise<AgentToolResult>;
}

export interface AgentToolCall {
  name: string;
  arguments: unknown;
  invocationId?: string;
}

export interface AgentToolExecutionHooks {
  onStart?(call: AgentToolCall, context: AgentToolContext): Promise<void> | void;
  onResult?(call: AgentToolCall, result: AgentToolResult, context: AgentToolContext): Promise<void> | void;
  onError?(call: AgentToolCall, error: unknown, context: AgentToolContext): Promise<void> | void;
  requiresApproval?(tool: AgentTool, call: AgentToolCall, context: AgentToolContext): Promise<boolean> | boolean;
}

export class AgentToolExecutionError extends Error {
  constructor(readonly code: string, message: string, readonly retryable = false) {
    super(message);
    this.name = "AgentToolExecutionError";
  }
}

/** Single execution boundary for built-in and dynamically discovered tools. */
export class AgentToolExecutionPipeline {
  constructor(
    private readonly tools: Map<string, AgentTool>,
    private readonly hooks: AgentToolExecutionHooks = {},
  ) {}

  async execute(call: AgentToolCall, context: AgentToolContext): Promise<AgentToolResult> {
    const tool = this.tools.get(call.name);
    if (!tool) throw new AgentToolExecutionError("UNKNOWN_TOOL", `工具不存在：${call.name}`);
    const args = asArguments(call.arguments);
    if (await this.hooks.requiresApproval?.(tool, call, context)) {
      throw new AgentToolExecutionError("APPROVAL_REQUIRED", "此工具调用需要用户审批");
    }
    await this.hooks.onStart?.(call, context);
    try {
      const result = normalizeResult(await tool.execute(context, args));
      await this.hooks.onResult?.(call, result, context);
      return result;
    } catch (error) {
      await this.hooks.onError?.(call, error, context);
      throw error;
    }
  }

  async executeBatch(calls: readonly AgentToolCall[], context: AgentToolContext): Promise<AgentToolResult[]> {
    if (calls.length === 0) throw new AgentToolExecutionError("EMPTY_BATCH", "工具调用批次不能为空");
    const tools = calls.map((call) => {
      const tool = this.tools.get(call.name);
      if (!tool) throw new AgentToolExecutionError("UNKNOWN_TOOL", `工具不存在：${call.name}`);
      return tool;
    });
    const approvals = await Promise.all(calls.map((call, index) => this.hooks.requiresApproval?.(tools[index], call, context) ?? false));
    if (approvals.some(Boolean)) throw new AgentToolExecutionError("APPROVAL_REQUIRED", "包含需要审批的工具调用，批次未执行");
    const readOnly = tools.every((tool) => tool.definition().readOnly === true);
    return readOnly ? Promise.all(calls.map((call) => this.execute(call, context)))
      : this.executeSerial(calls, context);
  }

  private async executeSerial(calls: readonly AgentToolCall[], context: AgentToolContext): Promise<AgentToolResult[]> {
    const results: AgentToolResult[] = [];
    for (const call of calls) results.push(await this.execute(call, context));
    return results;
  }
}

function asArguments(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new AgentToolExecutionError("INVALID_ARGUMENTS", "工具参数必须是 JSON 对象");
  }
  return value as Record<string, unknown>;
}

function normalizeResult(value: AgentToolResult): AgentToolResult {
  if (!value || typeof value !== "object" || typeof value.ok !== "boolean") {
    throw new AgentToolExecutionError("INVALID_RESULT", "工具返回了无效结果");
  }
  return value;
}
