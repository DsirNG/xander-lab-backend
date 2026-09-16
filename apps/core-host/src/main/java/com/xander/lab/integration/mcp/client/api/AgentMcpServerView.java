package com.xander.lab.integration.mcp.client.api;

import com.xander.lab.integration.mcp.client.domain.McpRemoteTool;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一台远端 MCP 服务器对外的样子。
 *
 * <p>刻意不含 {@code headersEncrypted}：请求头里放的是第三方凭据，密文和明文都不该回到前端。
 * 界面只需要知道"有没有配凭据"，那是 {@code headersConfigured}。</p>
 */
public record AgentMcpServerView(Long id, Long ownerUserId, String serverKey, String displayName,
                                 String endpointUrl, boolean enabled, boolean platformOwned,
                                 boolean headersConfigured, List<McpRemoteTool> tools,
                                 String lastStatus, String lastError, LocalDateTime lastConnectedAt) {
}
