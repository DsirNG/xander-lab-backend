package com.xander.lab.agent.api;

import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.core.api.AccessTokenLookupPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStreamAuthHandshakeInterceptorTest {

    private final AccessTokenLookupPort accessTokenLookupPort = mock(AccessTokenLookupPort.class);
    private final AgentConversationService conversationService = mock(AgentConversationService.class);

    private final AgentStreamAuthHandshakeInterceptor interceptor =
            new AgentStreamAuthHandshakeInterceptor(accessTokenLookupPort, conversationService);

    private AgentConversation conversation(Long ownerId) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(ownerId);
        conversation.setStatus("running");
        conversation.setRunVersion(9L);
        return conversation;
    }

    private ServerHttpRequest request(String query, String authorization) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://api.dinqor.cn/ws/agent" + query));
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) headers.set("Authorization", authorization);
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    @Test
    void acceptsValidTokenAndOwnedConversation() {
        when(accessTokenLookupPort.resolveUserId("abc")).thenReturn(42L);
        when(conversationService.get(7L, 42L)).thenReturn(conversation(42L));
        ServerHttpRequest request = request("?conversationId=7&runVersion=9", "Bearer abc");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request, mock(ServerHttpResponse.class), null, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(AgentStreamAuthHandshakeInterceptor.ATTR_USER_ID, 42L)
                .containsEntry(AgentStreamAuthHandshakeInterceptor.ATTR_CONVERSATION_ID, 7L)
                .containsEntry(AgentStreamAuthHandshakeInterceptor.ATTR_RUN_VERSION, 9L);
    }

    @Test
    void rejectsInvalidTokenWith401() {
        when(accessTokenLookupPort.resolveUserId("bad")).thenReturn(null);
        ServerHttpRequest request = request("?conversationId=7&runVersion=9", "Bearer bad");
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsMissingConversationParamsWith400() {
        when(accessTokenLookupPort.resolveUserId("abc")).thenReturn(42L);
        ServerHttpRequest request = request("", "Bearer abc");
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsForeignConversationWith404() {
        when(accessTokenLookupPort.resolveUserId("abc")).thenReturn(42L);
        when(conversationService.get(7L, 42L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体会话不存在"));
        ServerHttpRequest request = request("?conversationId=7&runVersion=9", "Bearer abc");
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor.beforeHandshake(request, response, null, new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
    }

    @Test
    void acceptsTokenFromQueryFallback() {
        when(accessTokenLookupPort.resolveUserId("qtoken")).thenReturn(42L);
        when(conversationService.get(7L, 42L)).thenReturn(conversation(42L));
        ServerHttpRequest request =
                request("?conversationId=7&runVersion=9&token=qtoken", null);

        boolean accepted = interceptor.beforeHandshake(
                request, mock(ServerHttpResponse.class), null, new HashMap<>());

        assertThat(accepted).isTrue();
    }
}