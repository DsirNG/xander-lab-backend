package com.xander.lab.service;

import com.xander.lab.identity.application.AuthService;

import com.xander.lab.identity.security.UserContext;
import com.xander.lab.dto.BlogPostDTO;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.security.JwtUtil;
import com.xander.lab.dto.PageData;
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

    /** Returns the authorized user's posts for AI context and duplicate detection. */
    public PageData<BlogPostVO> list(Integer page, Integer size, String search) {
        String accessToken = requireAccessToken();
        try {
            UserContext.setUserId(Long.parseLong(jwtUtil.getSubject(accessToken)));
            return blogService.getMyBlogs(null, search, page, size);
        } finally {
            UserContext.clear();
        }
    }

    /** Loads one post only after ownership has been established. */
    public BlogPostVO get(Long id) {
        String accessToken = requireAccessToken();
        try {
            UserContext.setUserId(Long.parseLong(jwtUtil.getSubject(accessToken)));
            return blogService.getManagedBlogById(UserContext.getUserId(), id);
        } finally {
            UserContext.clear();
        }
    }

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

    public BlogPostVO update(Long id, String title, String summary, String content, String categoryId, java.util.List<String> tags) {
        String accessToken = resolveAccessToken();
        if (accessToken == null) throw new McpAuthorizationRequiredException();
        try {
            UserContext.setUserId(Long.parseLong(jwtUtil.getSubject(accessToken)));
            return blogService.updateBlog(id, title, summary, content, categoryId, null, tags);
        } finally {
            UserContext.clear();
        }
    }

    public void delete(Long id) {
        String accessToken = requireAccessToken();
        try {
            UserContext.setUserId(Long.parseLong(jwtUtil.getSubject(accessToken)));
            blogService.deleteBlog(id);
        } finally {
            UserContext.clear();
        }
    }

    public Long currentUserId() {
        String accessToken = resolveAccessToken();
        if (accessToken == null) return null;
        return Long.parseLong(jwtUtil.getSubject(accessToken));
    }

    private String requireAccessToken() {
        String accessToken = resolveAccessToken();
        if (accessToken == null) throw new McpAuthorizationRequiredException();
        return accessToken;
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
