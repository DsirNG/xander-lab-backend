package com.xander.lab.controller;

import com.xander.lab.dto.auth.TokenResponse;
import com.xander.lab.service.AuthService;
import com.xander.lab.service.McpCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Browser-only, one-time credential handoff for the local MCP server. */
@RestController
@RequestMapping("/mcp/auth")
@RequiredArgsConstructor
public class McpAuthController {
    private final AuthService authService;
    private final McpCredentialService credentialService;

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public String loginPage() {
        return "<!doctype html><meta charset='utf-8'><title>MCP 博客授权</title>"
                + "<main style='max-width:560px;margin:3rem auto;font-family:sans-serif'>"
                + "<h1>MCP 博客授权</h1><p>请从博客前端登录后粘贴 Access Token 和 Refresh Token。凭据仅以 AES-GCM 加密形式保存在本服务的 Redis 中。</p>"
                + "<form method='post' action='/mcp/auth/complete'><label>Access Token<br><textarea name='accessToken' required rows='5' style='width:100%'></textarea></label>"
                + "<label>Refresh Token（建议填写）<br><textarea name='refreshToken' rows='5' style='width:100%'></textarea></label><br><button>保存授权</button></form></main>";
    }

    @PostMapping(value = "/complete", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public String complete(@RequestParam String accessToken, @RequestParam(required = false) String refreshToken) {
        if (!authService.validateAccessToken(accessToken)) {
            return "<p>Access Token 无效或已过期。请重新登录博客后再试。</p>";
        }
        TokenResponse response = TokenResponse.builder().accessToken(accessToken).refreshToken(refreshToken).build();
        credentialService.save(response);
        return "<p>授权已完成。现在可以回到 AI 客户端发布文章。</p>";
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        TokenResponse credentials = credentialService.load();
        return Map.of("authorized", credentials != null && authService.validateAccessToken(credentials.getAccessToken()));
    }
}
