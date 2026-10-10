import type {
  AgentKnowledgeDraft,
  AgentKnowledgeFilter,
  AgentKnowledgeMaterial,
  AgentRecitationRepository,
} from "../interfaces/agent-recitation.js";

const LEVELS = new Set(["NEW", "FAMILIAR", "RECALLING", "APPLYING", "MASTERED"]);
const TYPES = new Set(["RECITATION", "CONCEPT", "MATH"]);

export class AgentRecitationError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "AgentRecitationError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function excerpt(content: string, search: string, length: number): string {
  if (content.length <= length) return content;
  const index = search ? content.toLocaleLowerCase().indexOf(search.toLocaleLowerCase()) : -1;
  if (index < 0) return `${content.slice(0, length)}…`;
  const start = Math.max(0, index - Math.floor(length / 3));
  const end = Math.min(content.length, start + length);
  return `${start > 0 ? "…" : ""}${content.slice(start, end)}${end < content.length ? "…" : ""}`;
}

export class AgentRecitationService {
  constructor(private readonly repository: AgentRecitationRepository) {}

  async queryKnowledge(userId: number, raw: unknown) {
    const input = isRecord(raw) ? raw : {};
    const search = typeof input.search === "string" ? input.search.trim().slice(0, 300) : "";
    const requestedLevel = typeof input.masteryLevel === "string" ? input.masteryLevel.trim().toUpperCase() : "";
    const masteryLevel = LEVELS.has(requestedLevel) ? requestedLevel : "";
    const dueOnly = input.dueOnly === true;
    const fullContent = input.fullContent === true;
    const limit = Math.min(30, Math.max(1, Number.isSafeInteger(input.limit) ? Number(input.limit) : 10), fullContent ? 3 : 30);
    const filter: AgentKnowledgeFilter = { search, masteryLevel, dueOnly, limit };
    const countFilter = { search, masteryLevel, dueOnly };
    const [items, matchedTotal, total, mastered, due] = await Promise.all([
      this.repository.searchMaterials(userId, filter),
      this.repository.countMaterials(userId, countFilter),
      this.repository.countMaterials(userId, { search: "", masteryLevel: "", dueOnly: false }),
      this.repository.countMaterials(userId, { search: "", masteryLevel: "MASTERED", dueOnly: false }),
      this.repository.countMaterials(userId, { search: "", masteryLevel: "", dueOnly: true }),
    ]);
    const now = Date.now();
    const mapped = items.map((item) => this.materialView(item, now, search, fullContent));
    const data = {
      ok: true,
      total,
      mastered,
      learning: Math.max(0, total - mastered),
      dueForReview: due,
      matched: mapped.length,
      matchedTotal,
      moreMatches: matchedTotal > mapped.length ? matchedTotal - mapped.length : 0,
      items: mapped,
    };
    const answer = mapped.length === 0
      ? `你的个人知识库中没有匹配“${search || "全部"}”的记录。`
      : `个人知识库共 ${total} 条，已掌握 ${mastered} 条，待复习 ${due} 条。当前返回 ${mapped.length} 条匹配记录：\n`
        + mapped.map((item) => `- ${item.title}（${item.masteryLevel}，${item.masteryScore} 分）：${item.contentExcerpt}`).join("\n")
        + (matchedTotal > mapped.length ? `\n还有 ${matchedTotal - mapped.length} 条匹配记录未返回。` : "");
    return { data, answer };
  }

  async createKnowledge(userId: number, raw: unknown) {
    if (!isRecord(raw)) throw new AgentRecitationError(400, "创建知识需要有效的标题、正文和知识类型");
    const title = typeof raw.title === "string" ? raw.title.trim() : "";
    const content = typeof raw.content === "string" ? raw.content.trim() : "";
    const kind = typeof raw.knowledgeType === "string" ? raw.knowledgeType.trim().toUpperCase() : "CONCEPT";
    if (!title || title.length > 100 || !content || content.length > 10_000 || !TYPES.has(kind)) {
      throw new AgentRecitationError(400, "创建知识需要有效的标题、正文和知识类型");
    }
    const draft: AgentKnowledgeDraft = { title, content, knowledgeType: kind as AgentKnowledgeDraft["knowledgeType"] };
    const created = await this.repository.createMaterial(userId, draft);
    return {
      data: {
        ok: true,
        id: created.id,
        title: created.title,
        knowledgeType: created.knowledgeType,
        testMode: created.testMode,
        masteryScore: created.masteryScore,
        masteryLevel: created.masteryLevel,
        knowledgeUrl: `/workspace/knowledge/${created.id}`,
      },
      answer: `已将“${created.title}”加入你的个人知识库，当前掌握度为新知识（${created.masteryScore} 分）。你可以在 /workspace/knowledge/${created.id} 打开它并开始测试。`,
    };
  }

  async openKnowledgeQuiz(userId: number, conversationId: number, raw: unknown) {
    const input = isRecord(raw) ? raw : {};
    const materialId = input.materialId;
    if (!Number.isSafeInteger(materialId) || (materialId as number) < 1) {
      throw new AgentRecitationError(400, "知识材料 ID 格式不正确");
    }
    const requestedCount = input.questionCount;
    const questionCount = Number.isSafeInteger(requestedCount)
      ? Math.max(3, Math.min(10, requestedCount as number)) : 5;
    const quiz = await this.repository.openQuiz(userId, materialId as number, conversationId);
    if (!quiz.content.trim()) throw new AgentRecitationError(409, "这条知识没有可用于出题的正文");
    return {
      data: {
        quizSessionId: quiz.quizSessionId,
        materialId: quiz.materialId,
        title: quiz.title,
        knowledgeType: quiz.knowledgeType,
        testMode: quiz.testMode,
        contentTruncated: quiz.contentTruncated,
      },
      source: quiz.content,
      questionCount,
    };
  }

  async recordKnowledgeQuiz(userId: number, conversationId: number, raw: unknown) {
    if (!isRecord(raw) || !Number.isSafeInteger(raw.quizSessionId) || (raw.quizSessionId as number) < 1
      || !Array.isArray(raw.items) || raw.items.length < 1 || raw.items.length > 20) {
      throw new AgentRecitationError(400, "测验评分结果格式不正确");
    }
    const items = raw.items.map((value) => {
      if (!isRecord(value) || typeof value.question !== "string" || !value.question.trim()
        || value.question.length > 500 || typeof value.userAnswer !== "string" || value.userAnswer.length > 1000
        || typeof value.comment !== "string" || value.comment.length > 500
        || typeof value.credit !== "number" || !Number.isFinite(value.credit)
        || value.credit < 0 || value.credit > 1) {
        throw new AgentRecitationError(400, "每道测验题都需要有效的题目、作答、评语和 0 到 1 之间的得分");
      }
      return {
        question: value.question.trim(),
        userAnswer: value.userAnswer.trim(),
        comment: value.comment.trim(),
        credit: value.credit,
      };
    });
    const verdict = typeof raw.verdict === "string" ? raw.verdict.trim() : "";
    if (verdict.length > 1000) throw new AgentRecitationError(400, "测验总评过长");
    const result = await this.repository.recordQuiz(userId, conversationId, {
      quizSessionId: raw.quizSessionId as number,
      verdict,
      items,
    });
    const nextReview = Date.parse(result.nextReviewAt);
    const reviewText = Number.isFinite(nextReview)
      ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeZone: "Asia/Shanghai" }).format(nextReview)
      : result.nextReviewAt;
    return {
      data: { ok: true, ...result },
      answer: `《${result.title}》测验完成：${result.correctCount}/${result.questionCount} 题完全正确，得分 ${result.score} 分。掌握度从 ${result.previousMasteryLevel} 更新为 ${result.masteryLevel}，下次复习时间为 ${reviewText}。${verdict ? `\n${verdict}` : ""}`,
    };
  }

  private materialView(item: AgentKnowledgeMaterial, now: number, search: string, fullContent: boolean) {
    const excerptLimit = fullContent ? 2_000 : 300;
    return {
      id: item.id,
      title: item.title,
      knowledgeType: item.knowledgeType,
      testMode: item.testMode,
      masteryScore: item.masteryScore,
      masteryLevel: item.masteryLevel,
      reviewCount: item.reviewCount,
      dueForReview: item.nextReviewAt !== null && Date.parse(item.nextReviewAt) <= now,
      nextReviewAt: item.nextReviewAt ?? "",
      knowledgeUrl: `/workspace/knowledge/${item.id}`,
      contentLength: item.content.length,
      contentExcerpt: excerpt(item.content, search, excerptLimit),
      truncated: item.content.length > excerptLimit,
    };
  }
}
