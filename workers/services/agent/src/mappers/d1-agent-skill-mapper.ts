import type { D1Binding } from "@dinqor/worker-runtime";
import type { AgentSkillCreate, AgentSkillRecord, AgentSkillRepository } from "../interfaces/agent-skill-repository.js";

const SKILL_COLUMNS = `
  id, user_id AS userId, skill_key AS skillKey, version, name, description,
  instructions, tool_names_json AS toolNamesJson, status,
  created_at AS createdAt, updated_at AS updatedAt`;

/** Stores each user Skill revision as an immutable, owner-scoped D1 row. */
export class D1AgentSkillMapper implements AgentSkillRepository {
  constructor(private readonly db: D1Binding) {}

  async listActive(userId: number): Promise<AgentSkillRecord[]> {
    const rows = await this.db.prepare(`
      SELECT ${SKILL_COLUMNS} FROM agent_skill
      WHERE user_id = ? AND status = 'ACTIVE'
      ORDER BY skill_key ASC, version DESC
    `).bind(userId).all<AgentSkillRecord>();
    return rows.results;
  }

  createVersion(userId: number, skill: AgentSkillCreate): Promise<AgentSkillRecord | null> {
    // Compute the next version in the same statement as INSERT so concurrent creates
    // cannot allocate the same version for this user's skill key.
    return this.db.prepare(`
      INSERT INTO agent_skill (
        user_id, skill_key, version, name, description, instructions,
        tool_names_json, status, created_at, updated_at
      )
      SELECT ?, ?, COALESCE(MAX(version), 0) + 1, ?, ?, ?, ?, 'ACTIVE',
             strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      FROM agent_skill WHERE user_id = ? AND skill_key = ?
      RETURNING ${SKILL_COLUMNS}
    `).bind(
      userId,
      skill.skillKey,
      skill.name,
      skill.description,
      skill.instructions,
      skill.toolNamesJson,
      userId,
      skill.skillKey,
    ).first<AgentSkillRecord>();
  }

  async archive(userId: number, skillId: number): Promise<boolean> {
    const row = await this.db.prepare(`
      UPDATE agent_skill
      SET status = 'ARCHIVED', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND user_id = ? AND status = 'ACTIVE'
      RETURNING id
    `).bind(skillId, userId).first<{ id: number }>();
    return row !== null;
  }
}
