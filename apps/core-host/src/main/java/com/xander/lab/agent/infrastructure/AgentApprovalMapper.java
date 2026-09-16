package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentApproval;
import org.apache.ibatis.annotations.Mapper;

/** Persistence gateway for durable agent approval decisions. */
@Mapper
public interface AgentApprovalMapper extends BaseMapper<AgentApproval> {
}
