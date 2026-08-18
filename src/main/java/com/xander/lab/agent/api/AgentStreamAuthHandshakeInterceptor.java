package com.xander.lab.agent.api;

import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.identity.application.AuthService;
import com.xander.lab.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WebSocket 握手鉴权：校验 Access Token 与会话归属，把 userId / conversationId /
 * runVersion 放入会话属性供 {@link AgentStreamWebSocketHandler} 使用。
 * 握手路径不在 /api/** 下，因此不能依赖 AuthInterceptor，必须在握手阶段自行完成鉴权。
 */
@Slf4j
@RequiredArgsConstructor
public class AgentStreamAuthHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_USER_ID = "userId";
    static final String ATTR_CONVERSATION_ID = "conversationId";
    static final String ATTR_RUN_VERSION = "runVersion";

    private final AuthService authService;
    private final AgentConversationService conversationService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Long userId = resolveUserId(request);
        if (userId == null) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }
        Long conversationId = queryLong(request, "conversationId");
        Long runVersion = queryLong(request, "runVersion");
        if (conversationId == null || runVersion == null) {
            reject(response, HttpStatus.BAD_REQUEST);
            return false;
        }
        try {
            // 归属校验（不存在/非本人）与 SSE 订阅同一语义；同时触发 stale 会话恢复。
            conversationService.get(conversationId, userId);
        } catch (ResponseStatusException exception) {
            log.debug("agent ws handshake rejected for conversation {}: {}",
                    conversationId, exception.getReason());
            reject(response, exception.getStatusCode().is4xxClientError()
                    ? exception.getStatusCode() : HttpStatusCode.valueOf(404));
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_CONVERSATION_ID, conversationId);
        attributes.put(ATTR_RUN_VERSION, runVersion);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手结果已由握手阶段决定，无需补充处理。
    }

    /** Token 优先取 Authorization 头（与 HTTP 通道一致），iOS 等客户端可回退到 token 查询参数。 */
    private Long resolveUserId(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(Constants.AUTHORIZATION_HEADER);
        String token = null;
        if (header != null && header.startsWith(Constants.TOKEN_PREFIX)) {
            token = header.substring(Constants.TOKEN_PREFIX.length());
        }
        if (token == null) {
            token = queryParam(request, "token");
        }
        return token == null ? null : authService.getUserIdFromAccessToken(token);
    }

    private String queryParam(ServerHttpRequest request, String name) {
        return UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().getFirst(name);
    }

    private Long queryLong(ServerHttpRequest request, String name) {
        String value = queryParam(request, name);
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void reject(ServerHttpResponse response, HttpStatusCode status) {
        try {
            response.setStatusCode(status);
            response.getBody().write(("{\"code\":" + status.value()
                    + ",\"message\":\"握手鉴权失败\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            log.debug("agent ws handshake rejection write failed: {}", exception.getMessage());
        }
        // 返回 false 即终止握手，由容器关闭响应。
    }
}
