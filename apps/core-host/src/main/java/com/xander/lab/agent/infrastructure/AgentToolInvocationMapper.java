package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentToolInvocation;
import org.apache.ibatis.annotations.Mapper;

/** Persistence gateway for model-requested tool invocation records. */
@Mapper
public interface AgentToolInvocationMapper extends BaseMapper<AgentToolInvocation> {
}
