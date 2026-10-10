import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  RecitationMaterialCreate,
  RecitationMaterialPatch,
  RecitationMaterialRow,
  RecitationQuizAttemptRow,
  RecitationRepository,
  RecitationSearch,
  RecitationAttemptRow,
} from "../interfaces/recitation-repository.js";

const MATERIAL_COLUMNS = `
  id, user_id AS userId, title, content, normalized_content AS normalizedContent,
  knowledge_type AS knowledgeType, test_mode AS testMode,
  mastery_score AS masteryScore, mastery_level AS masteryLevel,
  review_count AS reviewCount, last_review_at AS lastReviewAt,
  next_review_at AS nextReviewAt, archived_at AS archivedAt,
  created_at AS createdAt, updated_at AS updatedAt
`;

export class D1RecitationMapper implements RecitationRepository {
  constructor(private readonly db: D1Binding) {}

  createMaterial(input: RecitationMaterialCreate): Promise<RecitationMaterialRow | null> {
    return this.db.prepare(`
      INSERT INTO recitation_material (
        user_id, title, content, normalized_content, knowledge_type, test_mode
      ) VALUES (?, ?, ?, ?, ?, ?)
      RETURNING ${MATERIAL_COLUMNS}
    `).bind(
      input.userId, input.title, input.content, input.normalizedContent,
      input.knowledgeType, input.testMode,
    ).first<RecitationMaterialRow>();
  }

  findMaterial(userId: number, materialId: number): Promise<RecitationMaterialRow | null> {
    return this.db.prepare(`
      SELECT ${MATERIAL_COLUMNS} FROM recitation_material
      WHERE user_id = ? AND id = ? LIMIT 1
    `).bind(userId, materialId).first<RecitationMaterialRow>();
  }

  async searchMaterials(
    userId: number,
    query: RecitationSearch,
    now: string,
  ): Promise<RecitationMaterialRow[]> {
    const conditions = ["user_id = ?"];
    const values: (number | string | null)[] = [userId];
    if (query.search) {
      conditions.push("(title LIKE ? OR content LIKE ?)");
      values.push(`%${query.search}%`, `%${query.search}%`);
    }
    if (query.masteryLevel) {
      conditions.push("upper(mastery_level) = ?");
      values.push(query.masteryLevel.toUpperCase());
    }
    if (query.dueOnly) {
      conditions.push("next_review_at IS NOT NULL AND next_review_at <= ?");
      values.push(now);
    }
    if (query.archive === "ACTIVE") conditions.push("archived_at IS NULL");
    if (query.archive === "ARCHIVED") conditions.push("archived_at IS NOT NULL");
    const result = await this.db.prepare(`
      SELECT ${MATERIAL_COLUMNS} FROM recitation_material
      WHERE ${conditions.join(" AND ")}
      ORDER BY created_at DESC, id DESC LIMIT ?
    `).bind(...values, query.limit).all<RecitationMaterialRow>();
    return result.results;
  }

  async countMaterials(
    userId: number,
    query: Omit<RecitationSearch, "limit" | "archive">,
    now: string,
  ): Promise<number> {
    const conditions = ["user_id = ?", "archived_at IS NULL"];
    const values: (number | string | null)[] = [userId];
    if (query.search) {
      conditions.push("(title LIKE ? OR content LIKE ?)");
      values.push(`%${query.search}%`, `%${query.search}%`);
    }
    if (query.masteryLevel) {
      conditions.push("upper(mastery_level) = ?");
      values.push(query.masteryLevel.toUpperCase());
    }
    if (query.dueOnly) {
      conditions.push("next_review_at IS NOT NULL AND next_review_at <= ?");
      values.push(now);
    }
    const result = await this.db.prepare(`
      SELECT COUNT(*) AS count FROM recitation_material
      WHERE ${conditions.join(" AND ")}
    `).bind(...values).first<{ count: number }>();
    return result?.count ?? 0;
  }

  async updateMaterial(
    userId: number,
    materialId: number,
    patch: RecitationMaterialPatch,
  ): Promise<boolean> {
    const sets: string[] = [];
    const values: (number | string | null)[] = [];
    const add = (column: string, value: string | number | null) => {
      sets.push(`${column} = ?`);
      values.push(value);
    };
    if (patch.title !== undefined) add("title", patch.title);
    if (patch.content !== undefined) add("content", patch.content);
    if (patch.normalizedContent !== undefined) add("normalized_content", patch.normalizedContent);
    if (patch.knowledgeType !== undefined) add("knowledge_type", patch.knowledgeType);
    if (patch.testMode !== undefined) add("test_mode", patch.testMode);
    if (patch.resetMastery) {
      add("mastery_score", 0);
      add("mastery_level", "NEW");
      add("next_review_at", null);
    }
    if (sets.length === 0) return false;
    sets.push("updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')");
    values.push(userId, materialId);
    const result = await this.db.prepare(`
      UPDATE recitation_material SET ${sets.join(", ")}
      WHERE user_id = ? AND id = ?
    `).bind(...values).run();
    return result.meta.changes === 1;
  }

  async archiveMaterial(userId: number, materialId: number, archivedAt: string | null): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE recitation_material
      SET archived_at = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE user_id = ? AND id = ?
    `).bind(archivedAt, userId, materialId).run();
    return result.meta.changes === 1;
  }

  async deleteMaterial(userId: number, materialId: number): Promise<boolean> {
    const results = await this.db.batch([
      this.db.prepare("DELETE FROM knowledge_quiz_attempt WHERE user_id = ? AND material_id = ?")
        .bind(userId, materialId),
      this.db.prepare("DELETE FROM knowledge_quiz_session WHERE user_id = ? AND material_id = ?")
        .bind(userId, materialId),
      this.db.prepare("DELETE FROM recitation_attempt WHERE user_id = ? AND material_id = ?")
        .bind(userId, materialId),
      this.db.prepare("DELETE FROM recitation_material WHERE user_id = ? AND id = ?")
        .bind(userId, materialId),
    ]);
    return results[3]?.meta.changes === 1;
  }

  findAttempt(userId: number, attemptId: number): Promise<RecitationAttemptRow | null> {
    return this.db.prepare(`
      SELECT id, user_id AS userId, material_id AS materialId,
             client_request_id AS clientRequestId, status, audio_url AS audioUrl,
             provider_task_id AS providerTaskId, provider_id AS providerId, model,
             transcript, result_json AS resultJson, score, error_message AS errorMessage,
             run_token AS runToken, poll_attempts AS pollAttempts,
             created_at AS createdAt, updated_at AS updatedAt
      FROM recitation_attempt WHERE user_id = ? AND id = ? LIMIT 1
    `).bind(userId, attemptId).first<RecitationAttemptRow>();
  }

  findAttemptByRequestId(userId: number, requestId: string): Promise<RecitationAttemptRow | null> {
    return this.db.prepare(`
      SELECT id, user_id AS userId, material_id AS materialId,
             client_request_id AS clientRequestId, status, audio_url AS audioUrl,
             provider_task_id AS providerTaskId, provider_id AS providerId, model,
             transcript, result_json AS resultJson, score, error_message AS errorMessage,
             run_token AS runToken, poll_attempts AS pollAttempts,
             created_at AS createdAt, updated_at AS updatedAt
      FROM recitation_attempt WHERE user_id = ? AND client_request_id = ? LIMIT 1
    `).bind(userId, requestId).first<RecitationAttemptRow>();
  }

  createAttempt(userId: number, materialId: number, requestId: string): Promise<RecitationAttemptRow | null> {
    return this.db.prepare(`
      INSERT INTO recitation_attempt (user_id, material_id, client_request_id, status)
      VALUES (?, ?, ?, 'UPLOADING')
      RETURNING id, user_id AS userId, material_id AS materialId,
                client_request_id AS clientRequestId, status, audio_url AS audioUrl,
                provider_task_id AS providerTaskId, provider_id AS providerId, model,
                transcript, result_json AS resultJson, score, error_message AS errorMessage,
                run_token AS runToken, poll_attempts AS pollAttempts,
                created_at AS createdAt, updated_at AS updatedAt
    `).bind(userId, materialId, requestId).first<RecitationAttemptRow>();
  }

  async setAttemptAudio(userId: number, attemptId: number, objectKey: string): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE recitation_attempt SET audio_url = ?, status = 'PENDING', error_message = NULL,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE user_id = ? AND id = ? AND status = 'UPLOADING'
    `).bind(objectKey, userId, attemptId).run();
    return result.meta.changes === 1;
  }

  async failAttempt(userId: number, attemptId: number, message: string): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE recitation_attempt SET status = 'FAILED', error_message = ?, run_token = NULL,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE user_id = ? AND id = ? AND status IN ('UPLOADING', 'PENDING')
    `).bind(message, userId, attemptId).run();
    return result.meta.changes === 1;
  }

  async listAttempts(userId: number, materialId: number): Promise<RecitationAttemptRow[]> {
    const result = await this.db.prepare(`
      SELECT id, user_id AS userId, material_id AS materialId,
             client_request_id AS clientRequestId, status, audio_url AS audioUrl,
             provider_task_id AS providerTaskId, provider_id AS providerId, model,
             transcript, result_json AS resultJson, score, error_message AS errorMessage,
             run_token AS runToken, poll_attempts AS pollAttempts,
             created_at AS createdAt, updated_at AS updatedAt
      FROM recitation_attempt
      WHERE user_id = ? AND material_id = ?
      ORDER BY created_at DESC, id DESC
    `).bind(userId, materialId).all<RecitationAttemptRow>();
    return result.results;
  }

  async listAttemptsByStatus(status: string, limit: number): Promise<RecitationAttemptRow[]> {
    const result = await this.db.prepare(`
      SELECT id, user_id AS userId, material_id AS materialId,
             client_request_id AS clientRequestId, status, audio_url AS audioUrl,
             provider_task_id AS providerTaskId, provider_id AS providerId, model,
             transcript, result_json AS resultJson, score, error_message AS errorMessage,
             run_token AS runToken, poll_attempts AS pollAttempts,
             created_at AS createdAt, updated_at AS updatedAt
      FROM recitation_attempt WHERE status = ? ORDER BY updated_at ASC, id ASC LIMIT ?
    `).bind(status, limit).all<RecitationAttemptRow>();
    return result.results;
  }

  async claimAttempt(
    attemptId: number, expectedStatus: string, nextStatus: string, token: string, incrementPoll: boolean,
  ): Promise<boolean> {
    const pollUpdate = incrementPoll ? ", poll_attempts = poll_attempts + 1" : "";
    const result = await this.db.prepare(`
      UPDATE recitation_attempt SET status = ?, run_token = ?${pollUpdate},
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND status = ?
    `).bind(nextStatus, token, attemptId, expectedStatus).run();
    return result.meta.changes === 1;
  }

  async markAttemptSubmitted(
    attemptId: number, token: string, providerId: number, model: string, taskId: string,
  ): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE recitation_attempt SET status = 'PROCESSING', provider_id = ?, model = ?,
        provider_task_id = ?, run_token = NULL, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND status = 'SUBMITTING' AND run_token = ?
    `).bind(providerId, model, taskId, attemptId, token).run();
    return result.meta.changes === 1;
  }

  async releaseAttemptForPoll(attemptId: number, token: string): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE recitation_attempt SET status = 'PROCESSING', run_token = NULL,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND status = 'POLLING' AND run_token = ?
    `).bind(attemptId, token).run();
    return result.meta.changes === 1;
  }

  async failClaimedAttempt(attemptId: number, expectedStatus: string, token: string, message: string): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE recitation_attempt SET status = 'FAILED', error_message = ?, run_token = NULL,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND status = ? AND run_token = ?
    `).bind(message, attemptId, expectedStatus, token).run();
    return result.meta.changes === 1;
  }

  async recoverStaleAttemptClaims(cutoff: string): Promise<void> {
    await this.db.batch([
      this.db.prepare(`
        UPDATE recitation_attempt SET status = 'FAILED', error_message = '语音识别提交中断，请重新录音',
          run_token = NULL, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE status = 'SUBMITTING' AND updated_at < ?
      `).bind(cutoff),
      this.db.prepare(`
        UPDATE recitation_attempt SET status = 'PROCESSING', run_token = NULL,
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE status = 'POLLING' AND updated_at < ?
      `).bind(cutoff),
    ]);
  }

  async completeAttempt(input: {
    attemptId: number; userId: number; materialId: number; token: string;
    transcript: string; resultJson: string; score: number; masteryLevel: string;
    lastReviewAt: string; nextReviewAt: string;
  }): Promise<boolean> {
    const results = await this.db.batch([
      this.db.prepare(`
        UPDATE recitation_attempt SET status = 'SUCCEEDED', transcript = ?, result_json = ?, score = ?,
          error_message = NULL, run_token = NULL, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND user_id = ? AND material_id = ? AND status = 'POLLING' AND run_token = ?
          AND EXISTS (SELECT 1 FROM recitation_material WHERE id = ? AND user_id = ?)
      `).bind(input.transcript, input.resultJson, input.score, input.attemptId, input.userId, input.materialId,
        input.token, input.materialId, input.userId),
      this.db.prepare(`
        UPDATE recitation_material SET mastery_score = ?, mastery_level = ?, review_count = review_count + 1,
          last_review_at = ?, next_review_at = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND user_id = ? AND EXISTS (
          SELECT 1 FROM recitation_attempt WHERE id = ? AND user_id = ? AND status = 'SUCCEEDED' AND result_json = ?
        )
      `).bind(input.score, input.masteryLevel, input.lastReviewAt, input.nextReviewAt,
        input.materialId, input.userId, input.attemptId, input.userId, input.resultJson),
    ]);
    return (results[0]?.meta.changes ?? 0) === 1;
  }

  async listQuizzes(userId: number, materialId: number): Promise<RecitationQuizAttemptRow[]> {
    const result = await this.db.prepare(`
      SELECT id, material_id AS materialId, test_mode AS testMode,
             question_count AS questionCount, correct_count AS correctCount,
             score, verdict, items_json AS itemsJson, created_at AS createdAt
      FROM knowledge_quiz_attempt
      WHERE user_id = ? AND material_id = ?
      ORDER BY created_at DESC, id DESC LIMIT 100
    `).bind(userId, materialId).all<RecitationQuizAttemptRow>();
    return result.results;
  }
}
