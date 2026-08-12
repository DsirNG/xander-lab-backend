package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {
}