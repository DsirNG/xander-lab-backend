import type { AgentTool } from "./agent-tool-runtime.js";
import { AgentToolExecutionPipeline, type AgentToolCall, type AgentToolContext, type AgentToolResult } from "./agent-tool-runtime.js";
import { AgentToolHandlerRegistry, type AgentToolHandler } from "./agent-tool-handler-registry.js";

export type AgentToolFactory = (args: Record<string, unknown>) => AgentTool;

/** Request-scoped executor lookup used by the Agent turn coordinator. */
export class AgentToolDispatcher {
  private readonly factories = new Map<string, AgentToolFactory>();
  private readonly pipeline: AgentToolExecutionPipeline;
  private readonly handlers = new AgentToolHandlerRegistry();

  constructor(private readonly tools: Map<string, AgentTool>) {
    // The map is request-scoped and intentionally shared with the pipeline so
    // dynamically discovered tools use the same execution boundary.
    this.pipeline = new AgentToolExecutionPipeline(this.tools);
  }

  register(tool: AgentTool): void {
    this.tools.set(tool.definition().name, tool);
  }

  registerFactory(name: string, factory: AgentToolFactory): void {
    this.factories.set(name, factory);
  }

  registerHandler(name: string, handler: AgentToolHandler): void {
    this.handlers.register(name, handler);
  }

  resolve(name: string, args: Record<string, unknown>): AgentTool | undefined {
    return this.tools.get(name) ?? this.factories.get(name)?.(args);
  }

  async execute(call: AgentToolCall, context: AgentToolContext): Promise<AgentToolResult> {
    if (this.handlers.has(call.name)) return this.handlers.execute(call, context);
    const tool = this.resolve(call.name, call.arguments as Record<string, unknown>);
    if (!tool) throw new Error(`工具未注册：${call.name}`);
    this.tools.set(call.name, tool);
    return this.executePipeline(call, context);
  }

  async executePipeline(call: AgentToolCall, context: AgentToolContext): Promise<AgentToolResult> {
    return this.pipeline.execute(call, context);
  }

  has(name: string): boolean {
    return this.tools.has(name);
  }

  hasHandler(name: string): boolean {
    return this.handlers.has(name);
  }

  get(name: string): AgentTool | undefined {
    return this.tools.get(name);
  }
}
