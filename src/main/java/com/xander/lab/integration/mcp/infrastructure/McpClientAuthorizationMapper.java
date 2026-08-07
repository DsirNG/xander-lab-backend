package com.xander.lab.integration.mcp.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persistence access for MCP client authorizations. */
@Mapper
public interface McpClientAuthorizationMapper extends BaseMapper<McpClientAuthorization> { }
