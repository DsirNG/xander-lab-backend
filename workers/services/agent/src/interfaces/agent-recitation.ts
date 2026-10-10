export interface AgentKnowledgeFilter {
  search: string;
  masteryLevel: string;
  dueOnly: boolean;
  limit: number;
}

export interface AgentKnowledgeMaterial {
  id: number;
  title: string;
  content: string;
  knowledgeType: string;
  testMode: string;
  masteryScore: number;
  masteryLevel: string;
  reviewCount: number;
  nextReviewAt: string | null;
}

export interface AgentKnowledgeDraft {
  title: string;
  content: string;
  knowledgeType: "RECITATION" | "CONCEPT" | "MATH";
}

export interface AgentKnowledgeQuiz {
  quizSessionId: number;
  materialId: number;
  title: string;
  knowledgeType: string;
  testMode: string;
  content: string;
  contentTruncated: boolean;
}

export interface AgentKnowledgeQuizResult {
  attemptId: number;
  materialId: number;
  title: string;
  score: number;
  questionCount: number;
  correctCount: number;
  masteryLevel: string;
  previousMasteryLevel: string;
  reviewCount: number;
  nextReviewAt: string;
}

export interface AgentRecitationRepository {
  searchMaterials(userId: number, filter: AgentKnowledgeFilter): Promise<AgentKnowledgeMaterial[]>;
  countMaterials(userId: number, filter: Omit<AgentKnowledgeFilter, "limit">): Promise<number>;
  createMaterial(userId: number, draft: AgentKnowledgeDraft): Promise<AgentKnowledgeMaterial>;
  openQuiz(userId: number, materialId: number, conversationId: number): Promise<AgentKnowledgeQuiz>;
  recordQuiz(userId: number, conversationId: number, input: {
    quizSessionId: number;
    verdict: string;
    items: Array<{ question: string; userAnswer: string; comment: string; credit: number }>;
  }): Promise<AgentKnowledgeQuizResult>;
}
