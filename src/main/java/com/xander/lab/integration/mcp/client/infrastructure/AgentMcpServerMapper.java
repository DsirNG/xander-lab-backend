package com.xander.lab.integration.mcp.client.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.integration.mcp.client.domain.AgentMcpServer;
import org.apache.ibatis.annotations.Mapper;

/** 远端 MCP 服务器配置的持久化网关。 */
@Mapper
public interface AgentMcpServerMapper extends BaseMapper<AgentMcpServer> {
}
