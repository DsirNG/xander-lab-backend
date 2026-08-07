package com.xander.lab.integration.mcp.api;

import com.xander.lab.identity.api.LoginRequest;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.application.AuthService;
import com.xander.lab.integration.mcp.application.McpClientAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Browser-only, one-time authorization handoff for an MCP client installation. */
@RestController
@RequestMapping("/api/mcp/auth")
@RequiredArgsConstructor
public class McpAuthorizationController {
    private final AuthService authService;
    private final McpClientAuthorizationService authorizationService;

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public String loginPage(@RequestParam String clientId, @RequestParam String code) {
        return "<!doctype html><meta charset='utf-8'><title>MCP 博客授权</title>"
                + "<main style='max-width:560px;margin:3rem auto;font-family:sans-serif'>"
                + "<h1>MCP 博客授权</h1><p>请使用博客账号登录一次。该授权仅绑定到当前 MCP 客户端。</p>"
                + "<form method='post' action='/api/mcp/auth/complete'><input type='hidden' name='clientId' value='" + html(clientId) + "'><input type='hidden' name='code' value='" + html(code) + "'><label>账号或邮箱<br><input name='account' autocomplete='username' required style='width:100%'></label><br>"
                + "<label>密码<br><input type='password' name='password' autocomplete='current-password' required style='width:100%'></label><br><button>登录并授权</button></form></main>";
    }

    @PostMapping(value = "/complete", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public String complete(@RequestParam String clientId, @RequestParam String code,
                           @RequestParam String account, @RequestParam String password) {
        try {
            LoginRequest request = new LoginRequest();
            request.setType("password");
            request.setAccount(account);
            request.setPassword(password);
            TokenResponse response = authService.login(request);
            authorizationService.authorize(clientId, code, response);
            return "<p>授权已完成。现在可以回到 AI 客户端发布文章。</p>";
        } catch (IllegalArgumentException exception) {
            return "<p>登录或授权失败。请回到 AI 客户端重新发起授权。</p>";
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String clientId, @RequestParam String clientSecret) {
        return Map.of("authorized", authorizationService.isAuthorized(clientId, clientSecret));
    }

    private String html(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
