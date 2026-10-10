import type {
  AgentKnowledgeDraft,
  AgentKnowledgeFilter,
  AgentKnowledgeQuiz,
  AgentKnowledgeQuizResult,
  AgentKnowledgeMaterial,
  AgentRecitationRepository,
} from "../interfaces/agent-recitation.js";
import type { AgentModelEnvironment } from "../interfaces/agent-model-gateway.js";

interface CoreAgentRecitationEnvironment extends AgentModelEnvironment {
  CORE_SERVICE?: { fetch(request: Request): Promise<Response> };
}

interface CoreMaterialResponse extends Partial<AgentKnowledgeMaterial> {
  code?: unknown;
  data?: unknown;
}

function material(value: unknown): AgentKnowledgeMaterial {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Core returned an invalid knowledge record");
  const row = value as Record<string, unknown>;
  if (typeof row.id !== "number" || !Number.isSafeInteger(row.id) || row.id <= 0
    || typeof row.title !== "string" || typeof row.content !== "string") {
    throw new Error("Core returned an invalid knowledge record");
  }
  return {
    id: row.id,
    title: row.title,
    content: row.content,
    knowledgeType: typeof row.knowledgeType === "string" ? row.knowledgeType : "CONCEPT",
    testMode: typeof row.testMode === "string" ? row.testMode : "AUTO",
    masteryScore: typeof row.masteryScore === "number" ? row.masteryScore : 0,
    masteryLevel: typeof row.masteryLevel === "string" ? row.masteryLevel : "NEW",
    reviewCount: typeof row.reviewCount === "number" ? row.reviewCount : 0,
    nextReviewAt: typeof row.nextReviewAt === "string" ? row.nextReviewAt : null,
  };
}

/** Agent's HTTPS adapter for Core-owned, owner-scoped recitation materials. */
export class CoreAgentRecitationMapper implements AgentRecitationRepository {
  constructor(private readonly env: CoreAgentRecitationEnvironment) {}

  async searchMaterials(userId: number, filter: AgentKnowledgeFilter): Promise<AgentKnowledgeMaterial[]> {
    const result = await this.call<{ materials?: unknown }>("/internal/agent/recitation/material/search", { userId, filter });
    if (!Array.isArray(result.materials)) throw new Error("Core returned an invalid knowledge list");
    return result.materials.map(material);
  }

  async countMaterials(userId: number, filter: Omit<AgentKnowledgeFilter, "limit">): Promise<number> {
    const result = await this.call<{ count?: unknown }>("/internal/agent/recitation/material/count", { userId, filter });
    if (typeof result.count !== "number" || !Number.isSafeInteger(result.count) || result.count < 0) {
      throw new Error("Core returned an invalid knowledge count");
    }
    return result.count;
  }

  async createMaterial(userId: number, draft: AgentKnowledgeDraft): Promise<AgentKnowledgeMaterial> {
    return material(await this.call("/internal/agent/recitation/material/create", { userId, draft }));
  }

  async openQuiz(userId: number, materialId: number, conversationId: number): Promise<AgentKnowledgeQuiz> {
    const value = await this.call<unknown>("/internal/agent/recitation/quiz/open", {
      userId, materialId, conversationId,
    });
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error("Core returned an invalid quiz session");
    }
    const row = value as Record<string, unknown>;
    if (!Number.isSafeInteger(row.quizSessionId) || (row.quizSessionId as number) < 1
      || !Number.isSafeInteger(row.materialId) || (row.materialId as number) !== materialId
      || typeof row.title !== "string" || typeof row.content !== "string") {
      throw new Error("Core returned an invalid quiz session");
    }
    return {
      quizSessionId: row.quizSessionId as number,
      materialId,
      title: row.title,
      knowledgeType: typeof row.knowledgeType === "string" ? row.knowledgeType : "CONCEPT",
      testMode: typeof row.testMode === "string" ? row.testMode : "AUTO",
      content: row.content,
      contentTruncated: row.contentTruncated === true,
    };
  }

  async recordQuiz(
    userId: number,
    conversationId: number,
    input: {
      quizSessionId: number;
      verdict: string;
      items: Array<{ question: string; userAnswer: string; comment: string; credit: number }>;
    },
  ): Promise<AgentKnowledgeQuizResult> {
    const value = await this.call<unknown>("/internal/agent/recitation/quiz/record", {
      userId, conversationId, ...input,
    });
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error("Core returned an invalid quiz result");
    }
    const row = value as Record<string, unknown>;
    const integers = ["attemptId", "materialId", "questionCount", "correctCount", "reviewCount"];
    if (integers.some((key) => !Number.isSafeInteger(row[key]) || (row[key] as number) < 0)
      || typeof row.title !== "string" || typeof row.score !== "number" || !Number.isFinite(row.score)
      || typeof row.masteryLevel !== "string" || typeof row.previousMasteryLevel !== "string"
      || typeof row.nextReviewAt !== "string") {
      throw new Error("Core returned an invalid quiz result");
    }
    return {
      attemptId: row.attemptId as number,
      materialId: row.materialId as number,
      title: row.title,
      score: row.score,
      questionCount: row.questionCount as number,
      correctCount: row.correctCount as number,
      masteryLevel: row.masteryLevel,
      previousMasteryLevel: row.previousMasteryLevel,
      reviewCount: row.reviewCount as number,
      nextReviewAt: row.nextReviewAt,
    };
  }

  private async call<T>(path: string, body: unknown): Promise<T> {
    const baseUrl = this.env.CORE_SERVICE_URL?.trim();
    const serviceToken = this.env.CORE_SERVICE_TOKEN;
    if (!baseUrl || !serviceToken) throw new Error("Core recitation contract is not configured");
    let url: URL;
    try {
      url = new URL(path, baseUrl);
    } catch {
      throw new Error("Core recitation contract is not configured");
    }
    if (url.protocol !== "https:" && url.hostname !== "127.0.0.1" && url.hostname !== "localhost") {
      throw new Error("Core recitation contract must use HTTPS");
    }
    const request = new Request(url, {
      method: "POST",
      headers: { "content-type": "application/json", "x-dinqor-service-token": serviceToken },
      body: JSON.stringify(body),
      redirect: "manual",
      signal: AbortSignal.timeout(5_000),
    });
    const response = this.env.CORE_SERVICE ? await this.env.CORE_SERVICE.fetch(request) : await fetch(request);
    if (!response.ok) throw new Error("Core recitation contract failed");
    let result: unknown;
    try {
      result = await response.json();
    } catch {
      throw new Error("Core recitation contract failed");
    }
    if (!result || typeof result !== "object" || Array.isArray(result)) throw new Error("Core recitation contract failed");
    const row = result as CoreMaterialResponse;
    if (row.code !== undefined) {
      if (row.code !== 200) throw new Error("Core recitation contract failed");
      return row.data as T;
    }
    return result as T;
  }
}
