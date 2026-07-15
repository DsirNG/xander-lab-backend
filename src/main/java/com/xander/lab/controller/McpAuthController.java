package com.xander.lab.controller;

import com.xander.lab.dto.auth.LoginRequest;
import com.xander.lab.dto.auth.TokenResponse;
import com.xander.lab.service.AuthService;
import com.xander.lab.service.McpBlogService;
import com.xander.lab.service.McpCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Browser-only, one-time credential handoff for the local MCP server. */
@RestController
@RequestMapping("/api/mcp/auth")
@RequiredArgsConstructor
public class McpAuthController {
    private final AuthService authService;
    private final McpCredentialService credentialService;
    private final McpBlogService mcpBlogService;

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public String loginPage() {
        return "<!doctype html><meta charset='utf-8'><title>MCP 博客授权</title>"
                + "<main style='max-width:560px;margin:3rem auto;font-family:sans-serif'>"
                + "<h1>MCP 博客授权</h1><p>请使用博客账号登录一次。密码只会提交给本站的登录接口，用于换取并加密保存发布凭据，不会发送给 AI。</p>"
                + "<form method='post' action='/api/mcp/auth/complete'><label>账号或邮箱<br><input name='account' autocomplete='username' required style='width:100%'></label><br>"
                + "<label>密码<br><input type='password' name='password' autocomplete='current-password' required style='width:100%'></label><br><button>登录并授权</button></form></main>";
    }

    @PostMapping(value = "/complete", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public String complete(@RequestParam String account, @RequestParam String password) {
        try {
            LoginRequest request = new LoginRequest();
            request.setType("password");
            request.setAccount(account);
            request.setPassword(password);
            TokenResponse response = authService.login(request);
            credentialService.save(response);
            return "<p>授权已完成。现在可以回到 AI 客户端发布文章。</p>";
        } catch (IllegalArgumentException e) {
            return "<p>登录失败：账号或密码不正确。<a href='/api/mcp/auth/login'>返回重试</a></p>";
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("authorized", mcpBlogService.isAuthorized());
    }
}
