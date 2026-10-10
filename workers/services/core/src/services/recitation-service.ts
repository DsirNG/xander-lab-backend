import type {
  RecitationMaterialCreate,
  RecitationMaterialPatch,
  RecitationMaterialRow,
  RecitationQuizAttemptRow,
  RecitationRepository,
  RecitationSearch,
  RecitationAttemptRow,
} from "../interfaces/recitation-repository.js";
import type { RecitationAudioStorage } from "../interfaces/recitation-audio.js";

const KNOWLEDGE_TYPES = new Set(["RECITATION", "CONCEPT", "MATH"]);
const TEST_MODES = new Set(["AUDIO_RECITATION", "AI_QA", "PRACTICE"]);

export class RecitationError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "RecitationError";
  }
}

export interface RecitationMaterialInput {
  title?: unknown;
  content?: unknown;
  knowledgeType?: unknown;
  testMode?: unknown;
}

export interface RecitationListInput {
  search?: string | null;
  masteryLevel?: string | null;
  dueOnly?: boolean;
  limit?: number;
  archive?: string | null;
}

function normalizeText(text: string): string {
  return Array.from(text)
    .filter((character) => /[\p{L}\p{N}]/u.test(character))
    .join("")
    .toLowerCase();
}

function parseJson(value: string | null): unknown {
  if (!value) return null;
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return null;
  }
}

function materialView(row: RecitationMaterialRow) {
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

function attemptView(row: RecitationAttemptRow, audioUrl = row.audioUrl) {
  return {
    id: row.id,
    materialId: row.materialId,
    status: row.status,
    audioUrl,
    transcript: row.transcript,
    result: parseJson(row.resultJson),
    score: row.score,
    errorMessage: row.errorMessage,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  };
}

function quizView(row: RecitationQuizAttemptRow) {
  return {
    id: row.id,
    materialId: row.materialId,
    testMode: row.testMode,
    questionCount: row.questionCount,
    correctCount: row.correctCount,
    score: row.score,
    verdict: row.verdict,
    items: parseJson(row.itemsJson) ?? [],
    createdAt: row.createdAt,
  };
}

function validId(id: number): boolean {
  return Number.isSafeInteger(id) && id > 0;
}

export class RecitationService {
  constructor(
    private readonly repository: RecitationRepository,
    private readonly audioStorage?: RecitationAudioStorage,
  ) {}

  async createMaterial(userId: number, input: RecitationMaterialInput) {
    this.requireUser(userId);
    const title = this.requiredText(input.title, "材料标题不能为空", 100, "材料标题不能超过100个字符");
    const content = this.requiredText(input.content, "背诵原文不能为空", 10_000, "背诵原文不能超过10000个字符");
    const normalizedContent = normalizeText(content);
    if (!normalizedContent) throw new RecitationError(400, "背诵原文必须包含中文、字母或数字");
    const knowledgeType = this.knowledgeType(input.knowledgeType);
    const testMode = this.testMode(input.testMode, knowledgeType);
    const created = await this.repository.createMaterial({
      userId,
      title,
      content,
      normalizedContent,
      knowledgeType,
      testMode,
    } satisfies RecitationMaterialCreate);
    if (!created) throw new RecitationError(503, "背诵服务暂不可用");
    return materialView(created);
  }

  async listMaterials(userId: number, input: RecitationListInput) {
    this.requireUser(userId);
    const rawLimit = input.limit ?? 100;
    const limit = Number.isSafeInteger(rawLimit) ? Math.max(1, Math.min(100, rawLimit)) : 100;
    const archive = input.archive?.trim().toUpperCase();
    const query: RecitationSearch = {
      search: input.search?.trim() ?? "",
      masteryLevel: input.masteryLevel?.trim() ?? "",
      dueOnly: input.dueOnly === true,
      limit,
      archive: archive === "ARCHIVED" || archive === "ALL" ? archive : "ACTIVE",
    };
    const rows = await this.repository.searchMaterials(userId, query, new Date().toISOString());
    return rows.map(materialView);
  }

  async getMaterial(userId: number, materialId: number) {
    const row = await this.requireMaterial(userId, materialId);
    return materialView(row);
  }

  async updateMaterial(userId: number, materialId: number, input: RecitationMaterialInput) {
    const current = await this.requireMaterial(userId, materialId);
    const patch: RecitationMaterialPatch = {};
    let changed = false;

    if (input.title !== undefined && input.title !== null) {
      if (typeof input.title !== "string") throw new RecitationError(400, "材料标题格式不正确");
      if (input.title.trim()) {
        const title = input.title.trim();
        if (title.length > 100) throw new RecitationError(400, "材料标题不能超过100个字符");
        if (title !== current.title) {
          patch.title = title;
          changed = true;
        }
      }
    }

    let contentChanged = false;
    if (input.content !== undefined && input.content !== null) {
      if (typeof input.content !== "string") throw new RecitationError(400, "背诵原文格式不正确");
      const content = input.content.trim();
      if (!content) throw new RecitationError(400, "背诵原文不能为空");
      if (content.length > 10_000) throw new RecitationError(400, "背诵原文不能超过10000个字符");
      const normalizedContent = normalizeText(content);
      if (!normalizedContent) throw new RecitationError(400, "背诵原文必须包含中文、字母或数字");
      contentChanged = normalizedContent !== current.normalizedContent;
      if (content !== current.content || normalizedContent !== current.normalizedContent) {
        patch.content = content;
        patch.normalizedContent = normalizedContent;
        changed = true;
      }
    }

    let nextKnowledgeType = current.knowledgeType;
    if (input.knowledgeType !== undefined && input.knowledgeType !== null) {
      nextKnowledgeType = this.knowledgeType(input.knowledgeType);
      if (nextKnowledgeType !== current.knowledgeType) {
        patch.knowledgeType = nextKnowledgeType;
        changed = true;
      }
    }

    let nextTestMode = current.testMode;
    if ((input.knowledgeType !== undefined && input.knowledgeType !== null)
      || (input.testMode !== undefined && input.testMode !== null)) {
      nextTestMode = this.testMode(input.testMode, nextKnowledgeType);
      if (nextTestMode !== current.testMode) {
        patch.testMode = nextTestMode;
        changed = true;
      }
    }

    if (contentChanged || nextTestMode !== current.testMode) patch.resetMastery = true;
    if (changed) {
      const updated = await this.repository.updateMaterial(userId, materialId, patch);
      if (!updated) throw new RecitationError(404, "背诵材料不存在");
    }
    return this.getMaterial(userId, materialId);
  }

  async archiveMaterial(userId: number, materialId: number, archived: boolean) {
    await this.requireMaterial(userId, materialId);
    const changed = await this.repository.archiveMaterial(
      userId,
      materialId,
      archived ? new Date().toISOString() : null,
    );
    if (!changed) throw new RecitationError(404, "背诵材料不存在");
    return this.getMaterial(userId, materialId);
  }

  async deleteMaterial(userId: number, materialId: number): Promise<void> {
    await this.requireMaterial(userId, materialId);
    const audioKeys = (await this.repository.listAttempts(userId, materialId))
      .map((attempt) => attempt.audioUrl)
      .filter((value): value is string => value !== null && !value.startsWith("https://"));
    const deleted = await this.repository.deleteMaterial(userId, materialId);
    if (!deleted) throw new RecitationError(404, "背诵材料不存在");
    if (this.audioStorage) {
      await Promise.all(audioKeys.map(async (key) => {
        try { await this.audioStorage!.delete(key); } catch { /* OSS lifecycle rules clean up orphaned recordings. */ }
      }));
    }
  }

  async createAttempt(userId: number, enabled: boolean): Promise<never> {
    this.requireUser(userId);
    if (!enabled) throw new RecitationError(503, "背诵音转文功能尚未启用");
    throw new RecitationError(503, "背诵音转文处理器尚未配置");
  }

  async listAttempts(userId: number, materialId: number) {
    await this.requireMaterial(userId, materialId);
    return Promise.all((await this.repository.listAttempts(userId, materialId)).map((row) => this.attemptView(row)));
  }

  async getAttempt(userId: number, attemptId: number) {
    this.requireUser(userId);
    if (!validId(attemptId)) throw new RecitationError(400, "录音任务 ID 格式不正确");
    const row = await this.repository.findAttempt(userId, attemptId);
    if (!row) throw new RecitationError(404, "录音任务不存在");
    return this.attemptView(row);
  }

  async listQuizzes(userId: number, materialId: number) {
    await this.requireMaterial(userId, materialId);
    return (await this.repository.listQuizzes(userId, materialId)).map(quizView);
  }

  private async requireMaterial(userId: number, materialId: number): Promise<RecitationMaterialRow> {
    this.requireUser(userId);
    if (!validId(materialId)) throw new RecitationError(400, "背诵材料 ID 格式不正确");
    const row = await this.repository.findMaterial(userId, materialId);
    if (!row) throw new RecitationError(404, "背诵材料不存在");
    return row;
  }

  private async attemptView(row: RecitationAttemptRow) {
    if (!row.audioUrl || !this.audioStorage || row.audioUrl.startsWith("https://")) return attemptView(row);
    try {
      return attemptView(row, await this.audioStorage.readUrl(row.audioUrl));
    } catch {
      return attemptView(row, null);
    }
  }

  private requireUser(userId: number): void {
    if (!validId(userId)) throw new RecitationError(401, "未登录或登录已过期");
  }

  private requiredText(value: unknown, emptyMessage: string, maxLength: number, tooLongMessage: string): string {
    if (typeof value !== "string" || !value.trim()) throw new RecitationError(400, emptyMessage);
    const text = value.trim();
    if (text.length > maxLength) throw new RecitationError(400, tooLongMessage);
    return text;
  }

  private knowledgeType(value: unknown): string {
    if (value === undefined || value === null) return "RECITATION";
    if (typeof value !== "string") throw new RecitationError(400, "不支持的知识类型");
    const result = value.trim().toUpperCase();
    if (!result) return "RECITATION";
    if (!KNOWLEDGE_TYPES.has(result)) throw new RecitationError(400, "不支持的知识类型");
    return result;
  }

  private testMode(value: unknown, knowledgeType: string): string {
    if (value === undefined || value === null) {
      return this.defaultTestMode(knowledgeType);
    }
    if (typeof value !== "string") throw new RecitationError(400, "不支持的测试方式");
    const result = value.trim().toUpperCase();
    if (!result) return this.defaultTestMode(knowledgeType);
    if (!TEST_MODES.has(result)) throw new RecitationError(400, "不支持的测试方式");
    return result;
  }

  private defaultTestMode(knowledgeType: string): string {
      if (knowledgeType === "CONCEPT") return "AI_QA";
      if (knowledgeType === "MATH") return "PRACTICE";
      return "AUDIO_RECITATION";
  }
}
