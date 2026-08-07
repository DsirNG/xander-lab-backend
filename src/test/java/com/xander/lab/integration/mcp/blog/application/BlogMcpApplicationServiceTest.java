package com.xander.lab.integration.mcp.blog.application;

import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.mcp.application.McpClientAuthorizationService;
import com.xander.lab.integration.mcp.security.McpClientContext;
import com.xander.lab.service.BlogService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogMcpApplicationServiceTest {
    @Mock McpClientAuthorizationService authorization;
    @Mock BlogService blogs;
    @InjectMocks BlogMcpApplicationService service;

    @AfterEach void clearContext() { UserContext.clear(); McpClientContext.clear(); }

    @Test void listDelegatesAsAuthorizedUserAndClearsContext() {
        McpClientContext.setClient("desktop-client-0001", "a-very-long-client-secret-for-test-0001");
        when(authorization.currentUserId("desktop-client-0001", "a-very-long-client-secret-for-test-0001")).thenReturn(42L);

        service.list(1, 10, "java");

        verify(blogs).getMyBlogs(null, "java", 1, 10);
        assertNull(UserContext.getUserId());
    }

    @Test void missingClientAuthorizationIsRejected() {
        assertThrows(BlogMcpApplicationService.McpAuthorizationRequiredException.class, () -> service.get(1L));
        verifyNoInteractions(blogs);
    }
}
