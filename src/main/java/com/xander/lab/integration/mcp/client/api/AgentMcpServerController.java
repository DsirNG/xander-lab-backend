package com.xander.lab.integration.mcp.client.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.mcp.client.application.AgentMcpServerService;
import com.xander.lab.integration.mcp.client.application.McpOAuthClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户自己的远端 MCP 服务器配置。
 *
 * <p>只能看到和改动自己名下的服务器：归属过滤在服务层，不靠前端隐藏。</p>
 */
@RestController
@RequestMapping("/api/agent/mcp-servers")
@RequiredArgsConstructor
public class AgentMcpServerController {

    private final AgentMcpServerService service;
    private final McpOAuthClientService oauthClientService;

    @GetMapping
    public Result<List<AgentMcpServerView>> list() {
        return Result.success(service.list(UserContext.getUserId()));
    }

    @PostMapping
    public Result<AgentMcpServerView> create(@Valid @RequestBody AgentMcpServerRequest request) {
        return Result.success(service.create(UserContext.getUserId(), request));
    }

    @PutMapping("/{id}")
    public Result<AgentMcpServerView> update(@PathVariable Long id,
                                             @Valid @RequestBody AgentMcpServerRequest request) {
        return Result.success(service.update(id, UserContext.getUserId(), request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id, UserContext.getUserId());
        return Result.success(null);
    }

    /** 探测连通性并刷新工具目录。连不上不是错误，结果落在返回体的 lastStatus/lastError 里。 */
    @PostMapping("/{id}/probe")
    public Result<AgentMcpServerView> probe(@PathVariable Long id) {
        return Result.success(service.probe(id, UserContext.getUserId()));
    }

    /** Discover the remote OAuth server and return a PKCE authorization URL. */
    @PostMapping("/{id}/oauth/start")
    public Result<Map<String, String>> startOAuth(@PathVariable Long id) {
        var start = oauthClientService.start(id, UserContext.getUserId());
        return Result.success(Map.of("serverId", String.valueOf(start.serverId()), "authorizationUrl", start.authorizationUrl()));
    }

    /** Exchange the callback code and persist the encrypted bearer token. */
    @PostMapping("/oauth/complete")
    public Result<Map<String, String>> completeOAuth(@Valid @RequestBody OAuthCallbackRequest request) {
        var completed = oauthClientService.complete(request.state(), request.code(), UserContext.getUserId());
        return Result.success(Map.of("serverId", String.valueOf(completed.serverId())));
    }

    public record OAuthCallbackRequest(@jakarta.validation.constraints.NotBlank String state,
                                       @jakarta.validation.constraints.NotBlank String code) { }
}
