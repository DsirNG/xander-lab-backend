package com.xander.lab.integration.mcp.security.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persistence access for MCP OAuth consent grants. */
@Mapper
public interface McpOAuthGrantMapper extends BaseMapper<McpOAuthGrant> { }
