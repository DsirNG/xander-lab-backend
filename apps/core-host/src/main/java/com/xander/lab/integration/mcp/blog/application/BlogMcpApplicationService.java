package com.xander.lab.integration.mcp.blog.application;

import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.common.PageData;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.publishing.application.BlogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Applies MCP blog commands as the user bound to the calling MCP client. */
@Service
@RequiredArgsConstructor
public class BlogMcpApplicationService {
    private final BlogService blogService;

    public boolean isAuthorized() { return currentUserId() != null; }

    public PageData<BlogPostVO> list(Integer page, Integer size, String search) {
        return asUser(userId -> blogService.getMyBlogs(null, search, page, size));
    }

    public BlogPostVO get(Long id) {
        return asUser(userId -> blogService.getManagedBlogById(userId, id));
    }

    public BlogPostVO create(BlogPostDTO post, boolean publish) {
        return asUser(userId -> blogService.createBlog(post, publish));
    }

    public BlogPostVO update(Long id, String title, String summary, String content, String categoryId, List<String> tags) {
        return asUser(userId -> blogService.updateBlog(id, title, summary, content, categoryId, null, tags));
    }

    public void delete(Long id) {
        asUser(userId -> { blogService.deleteBlog(id); return null; });
    }

    /** Returns the DinQorAI user represented by the client bound to this request. */
    public Long currentUserId() {
        return McpOAuthRequestContext.userId();
    }

    private <T> T asUser(java.util.function.Function<Long, T> command) {
        Long userId = currentUserId();
        if (userId == null) throw new McpAuthorizationRequiredException();
        try {
            UserContext.setUserId(userId);
            return command.apply(userId);
        } finally {
            UserContext.clear();
        }
    }

    /** Raised when an MCP client has not completed browser authorization. */
    public static class McpAuthorizationRequiredException extends RuntimeException { }
}
