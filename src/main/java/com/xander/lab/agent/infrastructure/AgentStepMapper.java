package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentStep;
import org.apache.ibatis.annotations.Mapper;

/** Persistence gateway for per-model-decision journal records. */
@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStep> {
}
