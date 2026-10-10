export interface PendingAgentApprovalInput {
  userId: number;
  conversationId: number;
  runVersion: number;
  invocationId: string;
  toolName: string;
  argumentsJson: string;
  requestFingerprint: string;
  summaryJson: string;
}

export interface AgentApprovalRecord {
  id: number;
  userId: number;
  conversationId: number;
  runVersion: number;
  invocationId: string;
  toolName: string;
  requestFingerprint: string;
  summaryJson: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  decisionReason: string | null;
  decidedAt: string | null;
  createdAt: string;
  updatedAt: string;
  invocationStatus?: string;
}

export interface AgentApprovalDecision {
  kind: "decided" | "not_found" | "conflict";
  approval?: AgentApprovalRecord;
  argumentsJson?: string;
  eventId?: number;
}

/** Owns the durable tool invocation and immutable human approval state in Agent D1. */
export interface AgentToolApprovalRepository {
  createPendingApproval(input: PendingAgentApprovalInput): Promise<{ approval: AgentApprovalRecord; eventId: number } | null>;
  listApprovals(userId: number, conversationId: number): Promise<AgentApprovalRecord[] | null>;
  decideApproval(
    userId: number,
    conversationId: number,
    approvalId: number,
    approved: boolean,
    reason: string | null,
  ): Promise<AgentApprovalDecision>;
  completeInvocation(
    userId: number,
    conversationId: number,
    runVersion: number,
    invocationId: string,
    result: unknown,
  ): Promise<number>;
  failInvocation(
    userId: number,
    conversationId: number,
    runVersion: number,
    invocationId: string,
    message: string,
    unknownOutcome: boolean,
  ): Promise<number>;
}

