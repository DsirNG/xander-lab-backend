package com.xander.lab.integration.mcp.client.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 远端服务器上探测到的一个工具。
 *
 * <p>这是 {@code agent_mcp_server.tools_json} 缓存的最小单元，也是渲染工具面时唯一需要的
 * 远端信息——建连只发生在探测和调用两个时刻，渲染工具面不碰网络。</p>
 */
public record McpRemoteTool(String name, String description, JsonNode inputSchema) {
}
