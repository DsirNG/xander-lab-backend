package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.api.AgentSkillRequest;
import com.xander.lab.agent.domain.AgentUserSkill;
import com.xander.lab.agent.infrastructure.AgentUserSkillMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** Owns user-private, versioned Skill instructions and their registered-tool capability limits. */
@Service
public class AgentUserSkillService {
    private final AgentUserSkillMapper mapper;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public AgentUserSkillService(AgentUserSkillMapper mapper, AgentToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.mapper = mapper; this.toolRegistry = toolRegistry; this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentUserSkill create(Long userId, AgentSkillRequest request) {
        Set<String> allowed = Set.copyOf(request.getToolNames());
        if (!toolRegistry.names().containsAll(allowed)) throw new IllegalArgumentException("Skill包含未注册工具");
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

    @Transactional(readOnly = true)
    public List<AgentUserSkill> listActive(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<AgentUserSkill>().eq(AgentUserSkill::getUserId, userId)
                .eq(AgentUserSkill::getStatus, AgentUserSkill.STATUS_ACTIVE).orderByAsc(AgentUserSkill::getSkillKey)
                .orderByDesc(AgentUserSkill::getVersion));
    }

    @Transactional(readOnly = true)
    public AgentUserSkill load(Long userId, String skillKey) {
        AgentUserSkill skill = mapper.selectOne(new LambdaQueryWrapper<AgentUserSkill>().eq(AgentUserSkill::getUserId, userId)
                .eq(AgentUserSkill::getSkillKey, skillKey).eq(AgentUserSkill::getStatus, AgentUserSkill.STATUS_ACTIVE)
                .orderByDesc(AgentUserSkill::getVersion).last("LIMIT 1"));
        if (skill == null) throw new IllegalArgumentException("Skill不存在或不可用");
        return skill;
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
