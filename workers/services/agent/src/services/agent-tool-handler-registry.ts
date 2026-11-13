import type { AgentToolCall, AgentToolContext, AgentToolResult } from "./agent-tool-runtime.js";

export type AgentToolHandler = (
  call: AgentToolCall,
  context: AgentToolContext,
) => Promise<AgentToolResult>;

/** Request-scoped handler registry for tool-specific orchestration and output shaping. */
export class AgentToolHandlerRegistry {
  private readonly handlers = new Map<string, AgentToolHandler>();

  register(name: string, handler: AgentToolHandler): void {
    this.handlers.set(name, handler);
  }

  has(name: string): boolean {
    return this.handlers.has(name);
  }

  resolve(name: string): AgentToolHandler | undefined {
    return this.handlers.get(name);
  }

  async execute(call: AgentToolCall, context: AgentToolContext): Promise<AgentToolResult> {
    const handler = this.resolve(call.name);
    if (!handler) throw new Error(`工具 Handler 未注册：${call.name}`);
    return handler(call, context);
  }
}
