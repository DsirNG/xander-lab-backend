package com.xander.lab.agent.tool.mcp.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.tool.mcp.domain.AgentMcpServer;
import org.apache.ibatis.annotations.Mapper;

/** 远端 MCP 服务器配置的持久化网关。 */
@Mapper
public interface AgentMcpServerMapper extends BaseMapper<AgentMcpServer> {
}
