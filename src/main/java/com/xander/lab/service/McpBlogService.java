package com.xander.lab.service;

import com.xander.lab.common.UserContext;
import com.xander.lab.dto.BlogPostDTO;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.dto.auth.TokenResponse;
import com.xander.lab.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class McpBlogService {
    private final McpCredentialService credentialService;
    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final BlogService blogService;

    public boolean isAuthorized() { return resolveAccessToken() != null; }

    public BlogPostVO create(BlogPostDTO post, boolean publish) {
        String accessToken = resolveAccessToken();
        if (accessToken == null) throw new McpAuthorizationRequiredException();
        try {
            UserContext.setUserId(Long.parseLong(jwtUtil.getSubject(accessToken)));
            return blogService.createBlog(post, publish);
        } finally {
            UserContext.clear();
        }
    }

    private String resolveAccessToken() {
        TokenResponse saved = credentialService.load();
        if (saved == null) return null;
        if (authService.validateAccessToken(saved.getAccessToken())) return saved.getAccessToken();
        if (saved.getRefreshToken() == null || saved.getRefreshToken().isBlank()) {
            credentialService.clear();
            return null;
        }
        try {
            TokenResponse refreshed = authService.refresh(saved.getRefreshToken());
            credentialService.save(refreshed);
            return refreshed.getAccessToken();
        } catch (Exception e) {
            credentialService.clear();
            return null;
        }
    }

    public static class McpAuthorizationRequiredException extends RuntimeException { }
}
