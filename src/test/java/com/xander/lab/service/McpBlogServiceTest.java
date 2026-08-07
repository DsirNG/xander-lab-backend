package com.xander.lab.service;

import com.xander.lab.identity.application.AuthService;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.security.JwtUtil;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.dto.PageData;
import com.xander.lab.dto.BlogPostVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpBlogServiceTest {
    @Mock McpCredentialService credentials;
    @Mock AuthService auth;
    @Mock JwtUtil jwt;
    @Mock BlogService blogs;
    @InjectMocks McpBlogService service;

    @AfterEach void clearContext() { UserContext.clear(); }

    @Test void listDelegatesAsAuthorizedUserAndClearsContext() {
        TokenResponse token = new TokenResponse(); token.setAccessToken("access");
        when(credentials.load()).thenReturn(token); when(auth.validateAccessToken("access")).thenReturn(true);
        when(jwt.getSubject("access")).thenReturn("42");
        service.list(1, 10, "java");
        verify(blogs).getMyBlogs(null, "java", 1, 10);
        org.junit.jupiter.api.Assertions.assertNull(UserContext.getUserId());
    }

    @Test void missingCredentialIsRejected() {
        when(credentials.load()).thenReturn(null);
        assertThrows(McpBlogService.McpAuthorizationRequiredException.class, () -> service.get(1L));
        verifyNoInteractions(blogs);
    }
}
