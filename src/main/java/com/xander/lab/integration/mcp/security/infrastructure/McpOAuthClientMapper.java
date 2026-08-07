package com.xander.lab.integration.mcp.security.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persistence access for public MCP OAuth clients. */
@Mapper
public interface McpOAuthClientMapper extends BaseMapper<McpOAuthClient> { }
