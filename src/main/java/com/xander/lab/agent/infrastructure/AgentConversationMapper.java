package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentConversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
}