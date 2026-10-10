import type { D1Binding, D1Result } from "@dinqor/worker-runtime";
import type { RecitationMaterialRow } from "../interfaces/recitation-repository.js";
import type {
  AgentQuizAttemptDraft,
  AgentQuizAttemptRow,
  AgentQuizRecordResult,
  AgentQuizSessionRow,
  AgentRecitationRepository,
} from "../interfaces/agent-recitation-repository.js";

const SESSION_COLUMNS = `
  id, user_id AS userId, material_id AS materialId,
  conversation_id AS conversationId, status, attempt_id AS attemptId,
  expires_at AS expiresAt, created_at AS createdAt, graded_at AS gradedAt
`;

const ATTEMPT_COLUMNS = `
  id, user_id AS userId, material_id AS materialId,
  quiz_session_id AS quizSessionId, conversation_id AS conversationId,
  test_mode AS testMode, question_count AS questionCount,
  correct_count AS correctCount, score, verdict, items_json AS itemsJson,
  created_at AS createdAt
`;

const MATERIAL_COLUMNS = `
  id, user_id AS userId, title, content, normalized_content AS normalizedContent,
  knowledge_type AS knowledgeType, test_mode AS testMode,
  mastery_score AS masteryScore, mastery_level AS masteryLevel,
  review_count AS reviewCount, last_review_at AS lastReviewAt,
  next_review_at AS nextReviewAt, archived_at AS archivedAt,
  created_at AS createdAt, updated_at AS updatedAt
`;

interface PreviousMasteryResult extends D1Result {
  results?: Array<{ masteryLevel: string }>;
}

export class D1AgentRecitationMapper implements AgentRecitationRepository {
  constructor(private readonly db: D1Binding) {}

  createQuizSession(input: {
    userId: number;
    materialId: number;
    conversationId: number | null;
    expiresAt: string;
    createdAt: string;
  }): Promise<AgentQuizSessionRow | null> {
    return this.db.prepare(`
      INSERT INTO knowledge_quiz_session (
        user_id, material_id, conversation_id, status, expires_at, created_at
      ) VALUES (?, ?, ?, 'OPEN', ?, ?)
      RETURNING ${SESSION_COLUMNS}
    `).bind(
      input.userId, input.materialId, input.conversationId, input.expiresAt, input.createdAt,
    ).first<AgentQuizSessionRow>();
  }

  findQuizSession(userId: number, quizSessionId: number): Promise<AgentQuizSessionRow | null> {
    return this.db.prepare(`
      SELECT ${SESSION_COLUMNS} FROM knowledge_quiz_session
      WHERE user_id = ? AND id = ? LIMIT 1
    `).bind(userId, quizSessionId).first<AgentQuizSessionRow>();
  }

  async listRecentQuizAttempts(
    userId: number,
    materialId: number,
    limit: number,
  ): Promise<AgentQuizAttemptRow[]> {
    const rows = await this.db.prepare(`
      SELECT ${ATTEMPT_COLUMNS} FROM knowledge_quiz_attempt
      WHERE user_id = ? AND material_id = ?
      ORDER BY created_at DESC, id DESC LIMIT ?
    `).bind(userId, materialId, limit).all<AgentQuizAttemptRow>();
    return rows.results;
  }

  async recordQuiz(input: AgentQuizAttemptDraft): Promise<AgentQuizRecordResult> {
    const results = await this.db.batch([
      this.db.prepare(`
        SELECT material.mastery_level AS masteryLevel
        FROM knowledge_quiz_session AS session
        JOIN recitation_material AS material
          ON material.id = session.material_id AND material.user_id = session.user_id
        WHERE session.id = ? AND session.user_id = ? LIMIT 1
      `).bind(input.quizSessionId, input.userId),
      this.db.prepare(`
        UPDATE knowledge_quiz_session
        SET status = 'GRADED', graded_at = ?
        WHERE id = ? AND user_id = ? AND status = 'OPEN' AND expires_at > ?
          AND (conversation_id IS NULL OR conversation_id = ?)
      `).bind(
        input.reviewedAt, input.quizSessionId, input.userId,
        input.reviewedAt, input.conversationId,
      ),
      this.db.prepare(`
        INSERT INTO knowledge_quiz_attempt (
          user_id, material_id, quiz_session_id, conversation_id, test_mode,
          question_count, correct_count, score, verdict, items_json, created_at
        )
        SELECT session.user_id, session.material_id, session.id, ?, material.test_mode, ?, ?, ?, ?, ?, ?
        FROM knowledge_quiz_session AS session
        JOIN recitation_material AS material
          ON material.id = session.material_id AND material.user_id = session.user_id
        WHERE session.id = ? AND session.user_id = ? AND session.status = 'GRADED'
          AND session.attempt_id IS NULL AND session.graded_at = ?
      `).bind(
        input.conversationId, input.questionCount, input.correctCount,
        input.score, input.verdict, input.itemsJson, input.reviewedAt,
        input.quizSessionId, input.userId, input.reviewedAt,
      ),
      this.db.prepare(`
        UPDATE recitation_material
        SET mastery_score = ?, mastery_level = ?, review_count = review_count + 1,
            last_review_at = ?, next_review_at = ?,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE user_id = ? AND id = (
          SELECT material_id FROM knowledge_quiz_session WHERE id = ? AND user_id = ?
        ) AND EXISTS (
          SELECT 1 FROM knowledge_quiz_attempt
          WHERE quiz_session_id = ? AND user_id = ? AND created_at = ?
        )
      `).bind(
        input.score, input.masteryLevel, input.reviewedAt, input.nextReviewAt,
        input.userId, input.quizSessionId, input.userId,
        input.quizSessionId, input.userId, input.reviewedAt,
      ),
      this.db.prepare(`
        UPDATE knowledge_quiz_session
        SET attempt_id = (
          SELECT id FROM knowledge_quiz_attempt
          WHERE quiz_session_id = ? AND user_id = ? AND created_at = ?
        )
        WHERE id = ? AND user_id = ? AND status = 'GRADED' AND attempt_id IS NULL
          AND graded_at = ?
      `).bind(
        input.quizSessionId, input.userId, input.reviewedAt,
        input.quizSessionId, input.userId, input.reviewedAt,
      ),
    ]);

    const session = await this.findQuizSession(input.userId, input.quizSessionId);
    if (!session) return { kind: "not_found" };
    if (session.conversationId !== null && session.conversationId !== input.conversationId) {
      return { kind: "conversation_mismatch" };
    }
    if (session.status === "OPEN") {
      return Date.parse(session.expiresAt) <= Date.parse(input.reviewedAt)
        ? { kind: "expired" }
        : { kind: "in_progress" };
    }
    if (!session.attemptId) return { kind: "in_progress" };

    const [attempt, material] = await Promise.all([
      this.db.prepare(`
        SELECT ${ATTEMPT_COLUMNS} FROM knowledge_quiz_attempt
        WHERE user_id = ? AND id = ? AND quiz_session_id = ? LIMIT 1
      `).bind(input.userId, session.attemptId, input.quizSessionId).first<AgentQuizAttemptRow>(),
      this.db.prepare(`
        SELECT ${MATERIAL_COLUMNS} FROM recitation_material
        WHERE user_id = ? AND id = ? LIMIT 1
      `).bind(input.userId, session.materialId).first<RecitationMaterialRow>(),
    ]);
    if (!attempt || !material) return { kind: "in_progress" };
    const previousMasteryLevel = (results[0] as PreviousMasteryResult | undefined)
      ?.results?.[0]?.masteryLevel ?? material.masteryLevel;
    return {
      kind: "recorded",
      firstWrite: results[1]?.meta.changes === 1,
      previousMasteryLevel: results[1]?.meta.changes === 1
        ? previousMasteryLevel : material.masteryLevel,
      session,
      attempt,
      material,
    };
  }
}
