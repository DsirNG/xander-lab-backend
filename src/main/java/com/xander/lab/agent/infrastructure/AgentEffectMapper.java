package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentEffect;
import org.apache.ibatis.annotations.Mapper;

/** Persistence gateway for logical side-effect identities. */
@Mapper
public interface AgentEffectMapper extends BaseMapper<AgentEffect> {
}
