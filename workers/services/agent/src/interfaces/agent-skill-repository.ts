export interface AgentSkillRecord {
  id: number;
  userId: number;
  skillKey: string;
  version: number;
  name: string;
  description: string;
  instructions: string;
  toolNamesJson: string;
  status: "ACTIVE" | "ARCHIVED";
  createdAt: string;
  updatedAt: string;
}

export interface AgentSkillCreate {
  skillKey: string;
  name: string;
  description: string;
  instructions: string;
  toolNamesJson: string;
}

/** Persistence port for immutable, user-owned Agent Skill versions. */
export interface AgentSkillRepository {
  listActive(userId: number): Promise<AgentSkillRecord[]>;
  createVersion(userId: number, skill: AgentSkillCreate): Promise<AgentSkillRecord | null>;
  archive(userId: number, skillId: number): Promise<boolean>;
}
