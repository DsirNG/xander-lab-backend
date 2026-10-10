import type {
  AgentQuizAttemptDraft,
  AgentQuizAttemptRow,
  AgentRecitationRepository,
} from "../interfaces/agent-recitation-repository.js";
import type { RecitationRepository } from "../interfaces/recitation-repository.js";
import type { RecitationService } from "./recitation-service.js";

const MAX_ITEMS = 20;
const MAX_QUESTION_CHARS = 500;
const MAX_ANSWER_CHARS = 1000;
const MAX_COMMENT_CHARS = 500;
const MAX_VERDICT_CHARS = 1000;
const SESSION_TTL_HOURS = 24;
const QUIZ_CONTENT_CHARS = 4000;
const SESSION_HISTORY_LIMIT = 3;

export class AgentRecitationError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "AgentRecitationError";
  }
}

export interface AgentMaterialDraftInput {
  title?: unknown;
  content?: unknown;
  knowledgeType?: unknown;
  testMode?: unknown;
}

export interface AgentMaterialFilterInput {
  search?: unknown;
  masteryLevel?: unknown;
  dueOnly?: unknown;
  limit?: unknown;
}

export interface AgentGradedItemInput {
  question?: unknown;
  userAnswer?: unknown;
  comment?: unknown;
  credit?: unknown;
}

function validId(value: number | null): value is number {
  return value !== null && Number.isSafeInteger(value) && value > 0;
}

function normalizedLimit(value: unknown, fallback: number, maximum: number): number {
  return typeof value === "number" && Number.isSafeInteger(value)
    ? Math.max(1, Math.min(maximum, value))
    : fallback;
}

function limited(value: unknown, max: number): string {
  const text = typeof value === "string" ? value.trim() : "";
  return text.length <= max ? text : `${text.slice(0, max)}…`;
}

function quizAttemptView(row: AgentQuizAttemptRow) {
  return {
    questionCount: row.questionCount,
    correctCount: row.correctCount,
    score: row.score,
    verdict: row.verdict,
    itemsJson: row.itemsJson,
    createdAt: row.createdAt,
  };
}

function masteryAt(score: number, reviewedAt: Date): { level: string; nextReviewAt: string } {
  const wholeScore = Math.trunc(score);
  const due = new Date(reviewedAt);
  if (wholeScore >= 90) {
    due.setUTCDate(due.getUTCDate() + 30);
    return { level: "MASTERED", nextReviewAt: due.toISOString() };
  }
  if (wholeScore >= 75) {
    due.setUTCDate(due.getUTCDate() + 7);
    return { level: "APPLYING", nextReviewAt: due.toISOString() };
  }
  if (wholeScore >= 60) {
    due.setUTCDate(due.getUTCDate() + 3);
    return { level: "RECALLING", nextReviewAt: due.toISOString() };
  }
  if (wholeScore >= 30) {
    due.setUTCDate(due.getUTCDate() + 1);
    return { level: "FAMILIAR", nextReviewAt: due.toISOString() };
  }
  due.setUTCHours(due.getUTCHours() + 12);
  return { level: "NEW", nextReviewAt: due.toISOString() };
}

export class AgentRecitationService {
  constructor(
    private readonly materials: RecitationRepository,
    private readonly quizzes: AgentRecitationRepository,
    private readonly recitation: RecitationService,
  ) {}

  async createMaterial(userId: number, draft: AgentMaterialDraftInput) {
    this.requireUser(userId);
    return this.recitation.createMaterial(userId, draft);
  }

  async searchMaterials(userId: number, filter: AgentMaterialFilterInput) {
    this.requireUser(userId);
    const search = typeof filter.search === "string" ? filter.search.trim() : "";
    const masteryLevel = typeof filter.masteryLevel === "string" ? filter.masteryLevel.trim() : "";
    const query = {
      search,
      masteryLevel,
      dueOnly: filter.dueOnly === true,
    };
    const rows = await this.materials.searchMaterials(userId, {
      ...query,
      limit: normalizedLimit(filter.limit, 100, 100),
      archive: "ACTIVE",
    }, new Date().toISOString());
    return rows.map((row) => this.materialView(row));
  }

  async countMaterials(userId: number, filter: AgentMaterialFilterInput): Promise<number> {
    this.requireUser(userId);
    return this.materials.countMaterials(userId, {
      search: typeof filter.search === "string" ? filter.search.trim() : "",
      masteryLevel: typeof filter.masteryLevel === "string" ? filter.masteryLevel.trim() : "",
      dueOnly: filter.dueOnly === true,
    }, new Date().toISOString());
  }

  async getMaterial(userId: number, materialId: number) {
    const row = await this.requireMaterial(userId, materialId);
    return this.materialView(row);
  }

  async openQuiz(userId: number, materialId: number, conversationId: number | null) {
    const material = await this.requireMaterial(userId, materialId);
    const content = material.content ?? "";
    const truncated = content.length > QUIZ_CONTENT_CHARS;
    const reviewedAt = new Date();
    const createdAt = reviewedAt.toISOString();
    const session = await this.quizzes.createQuizSession({
      userId,
      materialId,
      conversationId,
      createdAt,
      expiresAt: new Date(reviewedAt.getTime() + SESSION_TTL_HOURS * 60 * 60 * 1000).toISOString(),
    });
    if (!session) throw new AgentRecitationError(503, "测验服务暂不可用");
    const history = await this.quizzes.listRecentQuizAttempts(userId, materialId, SESSION_HISTORY_LIMIT);
    return {
      quizSessionId: session.id,
      materialId: material.id,
      title: material.title,
      knowledgeType: material.knowledgeType,
      testMode: material.testMode,
      content: truncated ? `${content.slice(0, QUIZ_CONTENT_CHARS)}…` : content,
      contentTruncated: truncated,
      contentLength: content.length,
      masteryScore: material.masteryScore,
      masteryLevel: material.masteryLevel,
      reviewCount: material.reviewCount,
      lastReviewAt: material.lastReviewAt,
      nextReviewAt: material.nextReviewAt,
      dueForReview: material.nextReviewAt !== null && Date.parse(material.nextReviewAt) <= reviewedAt.getTime(),
      recentQuizzes: history.map(quizAttemptView),
    };
  }

  async recordQuiz(
    userId: number,
    quizSessionId: number,
    conversationId: number | null,
    verdict: unknown,
    rawItems: unknown,
  ) {
    this.requireUser(userId);
    if (!validId(quizSessionId)) throw new AgentRecitationError(400, "测验会话 ID 格式不正确");
    const items = this.normalizeItems(rawItems);
    const earned = items.reduce((total, item) => total + item.credit, 0);
    const score = Math.round((earned / items.length) * 10000) / 100;
    const correctCount = items.filter((item) => item.credit >= 1).length;
    const reviewedAt = new Date();
    const reviewedAtText = reviewedAt.toISOString();
    const mastery = masteryAt(score, reviewedAt);
    const draft: AgentQuizAttemptDraft = {
      userId,
      quizSessionId,
      conversationId,
      questionCount: items.length,
      correctCount,
      score,
      verdict: limited(verdict, MAX_VERDICT_CHARS),
      itemsJson: JSON.stringify(items),
      masteryLevel: mastery.level,
      nextReviewAt: mastery.nextReviewAt,
      reviewedAt: reviewedAtText,
    };
    const result = await this.quizzes.recordQuiz(draft);
    if (result.kind === "not_found") throw new AgentRecitationError(404, "测验会话不存在");
    if (result.kind === "conversation_mismatch") {
      throw new AgentRecitationError(409, "测验会话不属于当前对话，请重新开始测验");
    }
    if (result.kind === "expired") throw new AgentRecitationError(409, "测验会话已过期，请重新开始测验");
    if (result.kind === "in_progress") throw new AgentRecitationError(409, "测验正在评分，请稍后重试");
    return {
      attemptId: result.attempt.id,
      materialId: result.material.id,
      title: result.material.title,
      score: result.attempt.score,
      questionCount: result.attempt.questionCount,
      correctCount: result.attempt.correctCount,
      masteryLevel: result.material.masteryLevel,
      previousMasteryLevel: result.previousMasteryLevel,
      reviewCount: result.material.reviewCount,
      nextReviewAt: result.material.nextReviewAt,
    };
  }

  private normalizeItems(value: unknown): Array<{
    question: string;
    userAnswer: string;
    comment: string;
    credit: number;
  }> {
    if (!Array.isArray(value) || value.length === 0) {
      throw new AgentRecitationError(400, "必须逐题给出题目、用户作答和得分，没有证据的分数不予记录");
    }
    if (value.length > MAX_ITEMS) {
      throw new AgentRecitationError(400, `一次最多记录 ${MAX_ITEMS} 道题的评分`);
    }
    return value.map((raw) => {
      if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
        throw new AgentRecitationError(400, "每道题都要写清题目");
      }
      const item = raw as AgentGradedItemInput;
      const question = limited(item.question, MAX_QUESTION_CHARS);
      if (!question) throw new AgentRecitationError(400, "每道题都要写清题目");
      if (typeof item.credit !== "number" || !Number.isFinite(item.credit)) {
        throw new AgentRecitationError(400, "每道题都要给出得分 credit（0 到 1），不能留空");
      }
      const credit = Math.round(Math.max(0, Math.min(1, item.credit)) * 100 + Number.EPSILON) / 100;
      return {
        question,
        userAnswer: limited(item.userAnswer, MAX_ANSWER_CHARS),
        comment: limited(item.comment, MAX_COMMENT_CHARS),
        credit,
      };
    });
  }

  private async requireMaterial(userId: number, materialId: number) {
    this.requireUser(userId);
    if (!validId(materialId)) throw new AgentRecitationError(400, "材料 ID 格式不正确");
    const row = await this.materials.findMaterial(userId, materialId);
    if (!row) throw new AgentRecitationError(404, "知识不存在");
    return row;
  }

  private requireUser(userId: number): void {
    if (!validId(userId)) throw new AgentRecitationError(401, "未登录或登录已过期");
  }

  private materialView(row: NonNullable<Awaited<ReturnType<RecitationRepository["findMaterial"]>>>) {
    return {
      id: row.id,
      title: row.title,
      content: row.content,
      characterCount: Array.from(row.normalizedContent).length,
      knowledgeType: row.knowledgeType,
      testMode: row.testMode,
      masteryScore: row.masteryScore,
      masteryLevel: row.masteryLevel,
      reviewCount: row.reviewCount,
      lastReviewAt: row.lastReviewAt,
      nextReviewAt: row.nextReviewAt,
      archivedAt: row.archivedAt,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}
