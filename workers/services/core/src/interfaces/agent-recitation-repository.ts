import type { RecitationMaterialRow } from "./recitation-repository.js";

export interface AgentQuizSessionRow {
  id: number;
  userId: number;
  materialId: number;
  conversationId: number | null;
  status: string;
  attemptId: number | null;
  expiresAt: string;
  createdAt: string;
  gradedAt: string | null;
}

export interface AgentQuizAttemptRow {
  id: number;
  userId: number;
  materialId: number;
  quizSessionId: number;
  conversationId: number | null;
  testMode: string;
  questionCount: number;
  correctCount: number;
  score: number;
  verdict: string | null;
  itemsJson: string | null;
  createdAt: string;
}

export interface AgentQuizAttemptDraft {
  userId: number;
  quizSessionId: number;
  conversationId: number | null;
  questionCount: number;
  correctCount: number;
  score: number;
  verdict: string;
  itemsJson: string;
  masteryLevel: string;
  nextReviewAt: string;
  reviewedAt: string;
}

export type AgentQuizRecordResult =
  | { kind: "not_found" }
  | { kind: "conversation_mismatch" }
  | { kind: "expired" }
  | { kind: "in_progress" }
  | {
    kind: "recorded";
    firstWrite: boolean;
    previousMasteryLevel: string;
    session: AgentQuizSessionRow;
    attempt: AgentQuizAttemptRow;
    material: RecitationMaterialRow;
  };

export interface AgentRecitationRepository {
  createQuizSession(input: {
    userId: number;
    materialId: number;
    conversationId: number | null;
    expiresAt: string;
    createdAt: string;
  }): Promise<AgentQuizSessionRow | null>;
  findQuizSession(userId: number, quizSessionId: number): Promise<AgentQuizSessionRow | null>;
  listRecentQuizAttempts(userId: number, materialId: number, limit: number): Promise<AgentQuizAttemptRow[]>;
  recordQuiz(input: AgentQuizAttemptDraft): Promise<AgentQuizRecordResult>;
}
