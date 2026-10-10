import type { AgentMcpServerRecord, AgentMcpRemoteTool } from "./agent-mcp-repository.js";

export interface AgentMcpConnection {
  listTools(server: AgentMcpServerRecord, headers: Record<string, string>): Promise<AgentMcpRemoteTool[]>;
  callTool(
    server: AgentMcpServerRecord,
    headers: Record<string, string>,
    toolName: string,
    argumentsValue: unknown,
  ): Promise<unknown>;
}
