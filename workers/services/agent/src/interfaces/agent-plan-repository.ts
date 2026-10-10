export interface AgentPlanItem {
  title: string;
  status: "PENDING" | "IN_PROGRESS" | "DONE" | "DROPPED";
  note?: string;
}

export interface AgentPlanRepository {
  replace(userId: number, conversationId: number, runVersion: number, items: AgentPlanItem[]): Promise<number | null>;
}
