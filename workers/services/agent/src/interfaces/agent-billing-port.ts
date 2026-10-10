export interface AgentTokenUsage {
  inputTokens: number;
  outputTokens: number;
  cachedTokens: number;
}

export interface AgentTurnCharge {
  userId: number;
  bizType: "agent-turn" | "blog-agent-illustration";
  bizKey: string;
  bizRef: string;
  inputTokens: number;
  outputTokens: number;
  cachedTokens: number;
  model: string | null;
  remark: string;
}

/** Core billing contract used to precheck balances and append idempotent Agent turn charges. */
export interface AgentBillingPort {
  ensureAvailable(userId: number): Promise<boolean>;
  chargeTokens(charge: AgentTurnCharge): Promise<void>;
}
