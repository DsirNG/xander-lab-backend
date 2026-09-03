package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentUserSkill;
import org.apache.ibatis.annotations.Mapper;

/** Persistence gateway for user-owned agent Skills. */
@Mapper
public interface AgentUserSkillMapper extends BaseMapper<AgentUserSkill> {
}
