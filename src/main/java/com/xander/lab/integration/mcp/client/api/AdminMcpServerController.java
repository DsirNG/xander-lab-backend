package com.xander.lab.integration.mcp.client.api;

import com.xander.lab.common.Result;
import com.xander.lab.integration.mcp.client.application.AgentMcpServerService;
import com.xander.lab.integration.mcp.client.domain.AgentMcpServer;
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

/**
 * 平台级远端 MCP 服务器配置，配好之后全部用户都能用。
 *
 * <p>路由前缀 /api/admin 由身份模块拦截器强制「登录 + ADMIN 角色」，
 * 所以这里不需要再自己判权限；归属固定为 {@link AgentMcpServer#PLATFORM_OWNER_ID}。</p>
 */
@RestController
@RequestMapping("/api/admin/mcp-servers")
@RequiredArgsConstructor
public class AdminMcpServerController {

    private final AgentMcpServerService service;

    @GetMapping
    public Result<List<AgentMcpServerView>> list() {
        return Result.success(service.list(AgentMcpServer.PLATFORM_OWNER_ID));
    }

    @PostMapping
    public Result<AgentMcpServerView> create(@Valid @RequestBody AgentMcpServerRequest request) {
        return Result.success(service.create(AgentMcpServer.PLATFORM_OWNER_ID, request));
    }

    @PutMapping("/{id}")
    public Result<AgentMcpServerView> update(@PathVariable Long id,
                                             @Valid @RequestBody AgentMcpServerRequest request) {
        return Result.success(service.update(id, AgentMcpServer.PLATFORM_OWNER_ID, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id, AgentMcpServer.PLATFORM_OWNER_ID);
        return Result.success(null);
    }

    @PostMapping("/{id}/probe")
    public Result<AgentMcpServerView> probe(@PathVariable Long id) {
        return Result.success(service.probe(id, AgentMcpServer.PLATFORM_OWNER_ID));
    }
}
