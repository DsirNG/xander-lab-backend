package com.xander.lab.integration.mcp.blog.application;

import com.xander.lab.auth.core.UserContext;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import com.xander.lab.publishing.application.BlogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BlogMcpApplicationServiceTest {
    @Mock BlogService blogs;
    @InjectMocks BlogMcpApplicationService service;

    @AfterEach void clearContext() { UserContext.clear(); McpOAuthRequestContext.clear(); }

    @Test void listDelegatesAsAuthorizedUserAndClearsContext() {
        McpOAuthRequestContext.set(42L, "desktop-client-0001", java.util.Set.of("blog:read"));

        service.list(1, 10, "java");

        verify(blogs).getMyBlogs(null, "java", 1, 10);
        assertNull(UserContext.getUserId());
    }

    @Test void missingClientAuthorizationIsRejected() {
        assertThrows(BlogMcpApplicationService.McpAuthorizationRequiredException.class, () -> service.get(1L));
        verifyNoInteractions(blogs);
    }
}
