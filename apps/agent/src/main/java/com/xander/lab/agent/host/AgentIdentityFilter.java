package com.xander.lab.agent.host;

import com.xander.lab.auth.core.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Restores the identity trusted by the Gateway for the Agent servlet request.
 *
 * <p>The Gateway validates the external access token, removes any caller-supplied
 * identity headers, and forwards the resulting account id as {@code X-User-Id}.
 * Agent controllers use {@link UserContext}, so the standalone host must bridge
 * that deployment-boundary header into the request thread before dispatch.</p>
 *
 * <p>This filter is intentionally scoped to the Agent host. It does not parse JWTs
 * or accept an external bearer token; authentication remains an edge concern, while
 * direct service exposure is prohibited by the deployment network boundary.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentIdentityFilter extends OncePerRequestFilter {

    static final String USER_ID_HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        UserContext.clear();
        Long userId = parseUserId(request.getHeader(USER_ID_HEADER));
        if (userId != null) {
            UserContext.setUserId(userId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long userId = Long.parseLong(header.trim());
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
