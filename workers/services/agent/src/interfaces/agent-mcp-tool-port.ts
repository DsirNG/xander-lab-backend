export interface AgentMcpToolDefinition {
  serverId: number;
  serverKey: string;
  serverName: string;
  toolName: string;
  fullName: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export interface AgentMcpToolPort {
  toolsForUser(userId: number): Promise<AgentMcpToolDefinition[]>;
  callApprovedTool(userId: number, serverId: number, toolName: string, argumentsValue: unknown): Promise<unknown>;
}
