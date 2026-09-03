package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentRun;
import org.apache.ibatis.annotations.Mapper;

/** Persistence gateway for durable agent turn records. */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
}
