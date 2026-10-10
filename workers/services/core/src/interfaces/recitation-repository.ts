export interface RecitationMaterialRow {
  id: number;
  userId: number;
  title: string;
  content: string;
  normalizedContent: string;
  knowledgeType: string;
  testMode: string;
  masteryScore: number;
  masteryLevel: string;
  reviewCount: number;
  lastReviewAt: string | null;
  nextReviewAt: string | null;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RecitationAttemptRow {
  id: number;
  userId: number;
  materialId: number;
  clientRequestId: string;
  status: string;
  audioUrl: string | null;
  providerTaskId: string | null;
  providerId: number | null;
  model: string | null;
  transcript: string | null;
  resultJson: string | null;
  score: number | null;
  errorMessage: string | null;
  runToken: string | null;
  pollAttempts: number;
  createdAt: string;
  updatedAt: string;
}

export interface RecitationQuizAttemptRow {
  id: number;
  materialId: number;
  testMode: string;
  questionCount: number;
  correctCount: number;
  score: number;
  verdict: string | null;
  itemsJson: string;
  createdAt: string;
}

export interface RecitationMaterialCreate {
  userId: number;
  title: string;
  content: string;
  normalizedContent: string;
  knowledgeType: string;
  testMode: string;
}

export interface RecitationMaterialPatch {
  title?: string;
  content?: string;
  normalizedContent?: string;
  knowledgeType?: string;
  testMode?: string;
  resetMastery?: boolean;
}

export interface RecitationSearch {
  search: string;
  masteryLevel: string;
  dueOnly: boolean;
  limit: number;
  archive: "ACTIVE" | "ARCHIVED" | "ALL";
}

export interface RecitationRepository {
  createMaterial(input: RecitationMaterialCreate): Promise<RecitationMaterialRow | null>;
  findMaterial(userId: number, materialId: number): Promise<RecitationMaterialRow | null>;
  searchMaterials(userId: number, query: RecitationSearch, now: string): Promise<RecitationMaterialRow[]>;
  countMaterials(userId: number, query: Omit<RecitationSearch, "limit" | "archive">, now: string): Promise<number>;
  updateMaterial(userId: number, materialId: number, patch: RecitationMaterialPatch): Promise<boolean>;
  archiveMaterial(userId: number, materialId: number, archivedAt: string | null): Promise<boolean>;
  deleteMaterial(userId: number, materialId: number): Promise<boolean>;
  findAttempt(userId: number, attemptId: number): Promise<RecitationAttemptRow | null>;
  findAttemptByRequestId(userId: number, requestId: string): Promise<RecitationAttemptRow | null>;
  createAttempt(userId: number, materialId: number, requestId: string): Promise<RecitationAttemptRow | null>;
  setAttemptAudio(userId: number, attemptId: number, objectKey: string): Promise<boolean>;
  failAttempt(userId: number, attemptId: number, message: string): Promise<boolean>;
  listAttempts(userId: number, materialId: number): Promise<RecitationAttemptRow[]>;
  listAttemptsByStatus(status: string, limit: number): Promise<RecitationAttemptRow[]>;
  claimAttempt(attemptId: number, expectedStatus: string, nextStatus: string, token: string, incrementPoll: boolean): Promise<boolean>;
  markAttemptSubmitted(attemptId: number, token: string, providerId: number, model: string, taskId: string): Promise<boolean>;
  releaseAttemptForPoll(attemptId: number, token: string): Promise<boolean>;
  failClaimedAttempt(attemptId: number, expectedStatus: string, token: string, message: string): Promise<boolean>;
  recoverStaleAttemptClaims(cutoff: string): Promise<void>;
  completeAttempt(input: {
    attemptId: number; userId: number; materialId: number; token: string;
    transcript: string; resultJson: string; score: number; masteryLevel: string;
    lastReviewAt: string; nextReviewAt: string;
  }): Promise<boolean>;
  listQuizzes(userId: number, materialId: number): Promise<RecitationQuizAttemptRow[]>;
}
