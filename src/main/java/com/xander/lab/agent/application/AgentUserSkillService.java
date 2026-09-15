package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.api.AgentSkillRequest;
import com.xander.lab.agent.api.AgentToolOption;
import com.xander.lab.agent.domain.AgentUserSkill;
import com.xander.lab.agent.infrastructure.AgentUserSkillMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** Owns user-private, versioned Skill instructions; reads go through {@link AgentSkillCatalog}. */
@Service
public class AgentUserSkillService {
    private final AgentUserSkillMapper mapper;
    private final AgentToolRegistry toolRegistry;
    private final AgentSkillCatalog catalog;
    private final ObjectMapper objectMapper;

    public AgentUserSkillService(AgentUserSkillMapper mapper, AgentToolRegistry toolRegistry,
                                AgentSkillCatalog catalog, ObjectMapper objectMapper) {
        this.mapper = mapper; this.toolRegistry = toolRegistry;
        this.catalog = catalog; this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentUserSkill create(Long userId, AgentSkillRequest request) {
        Set<String> allowed = Set.copyOf(request.getToolNames());
        if (!toolRegistry.allowedNames(userId).containsAll(allowed)) throw new IllegalArgumentException("Skill包含未注册工具");
        int version = mapper.selectList(new LambdaQueryWrapper<AgentUserSkill>()
                .eq(AgentUserSkill::getUserId, userId).eq(AgentUserSkill::getSkillKey, request.getSkillKey()))
                .stream().map(AgentUserSkill::getVersion).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
        AgentUserSkill skill = new AgentUserSkill();
        skill.setUserId(userId); skill.setSkillKey(request.getSkillKey()); skill.setVersion(version);
        skill.setName(request.getName().trim()); skill.setDescription(request.getDescription().trim());
        skill.setInstructions(request.getInstructions().trim()); skill.setToolNamesJson(write(request.getToolNames()));
        skill.setStatus(AgentUserSkill.STATUS_ACTIVE);
        if (mapper.insert(skill) != 1) throw new IllegalStateException("Skill创建失败");
        return skill;
    }

    /** 管理界面用的全部 ACTIVE 版本，含历史版本；模型看到的是每个 key 最新的那一条。 */
    @Transactional(readOnly = true)
    public List<AgentUserSkill> listActive(Long userId) {
        return catalog.allActiveUserSkills(userId);
    }

    /**
     * 可写进技能的已注册工具，供技能编排界面提供选择。
     *
     * <p>没有这个入口，用户只能凭记忆手写工具名，而 {@link #create} 会拿
     * {@link AgentToolRegistry#allowedNames(Long)} 严格求交——拼错一个字母就是一记 400，
     * 且没有任何地方能查到合法取值。名字按注册表顺序（字典序）返回，与提示词里的
     * 工具表一致。</p>
     *
     * <p>清单按用户收窄：用户自己配的远端 MCP 工具只出现在他自己这里，
     * 否则界面会给出一个他写了也调不动的工具名。</p>
     */
    @Transactional(readOnly = true)
    public List<AgentToolOption> listToolOptions(Long userId) {
        return toolRegistry.tools(userId).stream()
                .map(tool -> new AgentToolOption(tool.name(), tool.description()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentUserSkill load(Long userId, String skillKey) {
        return catalog.activeUserSkill(userId, skillKey)
                .orElseThrow(() -> new IllegalArgumentException("Skill不存在或不可用"));
    }

    @Transactional
    public void archive(Long userId, Long id) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<AgentUserSkill>().eq(AgentUserSkill::getId, id)
                .eq(AgentUserSkill::getUserId, userId).eq(AgentUserSkill::getStatus, AgentUserSkill.STATUS_ACTIVE)
                .set(AgentUserSkill::getStatus, AgentUserSkill.STATUS_ARCHIVED));
        if (updated != 1) throw new IllegalArgumentException("Skill不存在或已归档");
    }
    private String write(List<String> names) { try { return objectMapper.writeValueAsString(names); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
}
