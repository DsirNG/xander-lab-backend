import type { AgentSkillCreate, AgentSkillRecord, AgentSkillRepository } from "../interfaces/agent-skill-repository.js";

const SKILL_KEY = /^[a-z][a-z0-9-]{1,79}$/;
const TOOL_NAME = /^[a-z][a-z0-9_]{1,63}$/;
const REGISTERED_TOOLS = new Set(["publish_post"]);

export interface AgentSkillInput {
  skillKey: string;
  name: string;
  description: string;
  instructions: string;
  toolNames: string[];
}

export class AgentSkillValidationError extends Error {}

/** Owns validation and lifecycle rules for user-private Agent Skill versions. */
export class AgentSkillService {
  constructor(private readonly repository: AgentSkillRepository) {}

  list(userId: number): Promise<AgentSkillRecord[]> {
    return this.repository.listActive(userId);
  }

  listToolOptions(): Array<{ name: string; description: string }> {
    return [{
      name: "publish_post",
      description: "经用户审批后，将已有文章同步到其 CSDN 或掘金账号。",
    }];
  }

  async create(userId: number, input: AgentSkillInput): Promise<AgentSkillRecord> {
    const skill = this.validate(input);
    if (skill.toolNames.some((toolName) => !REGISTERED_TOOLS.has(toolName))) {
      throw new AgentSkillValidationError("Skill包含未注册工具");
    }
    const created = await this.repository.createVersion(userId, {
      skillKey: skill.skillKey,
      name: skill.name,
      description: skill.description,
      instructions: skill.instructions,
      toolNamesJson: JSON.stringify(skill.toolNames),
    });
    if (!created) throw new Error("Skill创建失败");
    return created;
  }

  archive(userId: number, skillId: number): Promise<boolean> {
    return this.repository.archive(userId, skillId);
  }

  private validate(input: AgentSkillInput): AgentSkillInput {
    const skillKey = input.skillKey.trim();
    const name = input.name.trim();
    const description = input.description.trim();
    const instructions = input.instructions.trim();
    if (!SKILL_KEY.test(skillKey)) throw new AgentSkillValidationError("Skill 标识格式不正确");
    if (!name || name.length > 120) throw new AgentSkillValidationError("Skill 名称不能为空且不能超过 120 个字符");
    if (!description || description.length > 500) {
      throw new AgentSkillValidationError("Skill 描述不能为空且不能超过 500 个字符");
    }
    if (!instructions || instructions.length > 12_000) {
      throw new AgentSkillValidationError("Skill 说明不能为空且不能超过 12000 个字符");
    }
    if (input.toolNames.length > 30 || input.toolNames.some((name) => !TOOL_NAME.test(name))) {
      throw new AgentSkillValidationError("Skill 工具列表格式不正确");
    }
    return { skillKey, name, description, instructions, toolNames: [...new Set(input.toolNames)] };
  }
}
