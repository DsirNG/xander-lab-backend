export interface AgentContentQueryResult {
  data: Record<string, unknown>;
  answer: string;
}

export class AgentContentContractError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

export interface AgentContentToolsPort {
  queryPosts(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult>;
  queryPlans(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult>;
  queryEmailReminders(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult>;
  queryUnsyncedPosts(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult>;
  createPlan(userId: number, args: Record<string, unknown>, requestId: string): Promise<AgentContentQueryResult>;
  updatePlan(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult>;
  createEmailReminder(userId: number, args: Record<string, unknown>, requestId: string): Promise<AgentContentQueryResult>;
  updateEmailReminder(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult>;
}
