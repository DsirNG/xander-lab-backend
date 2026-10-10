export interface AgentPublicationResult {
  platform: "csdn" | "juejin";
  externalId: string;
  url: string | null;
}

/** Cross-service port for Content-owned, owner-scoped external publication attempts. */
export interface AgentPublicationPort {
  findSucceeded(userId: number, postId: number, platform: "csdn" | "juejin"): Promise<AgentPublicationResult | null>;
  sync(userId: number, postId: number, platform: "csdn" | "juejin"): Promise<AgentPublicationResult>;
}
