package com.xander.lab.integration.mcp.security.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persistence access for hash-only MCP OAuth tokens. */
@Mapper
public interface McpOAuthTokenMapper extends BaseMapper<McpOAuthToken> { }
