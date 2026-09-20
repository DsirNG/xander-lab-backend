package com.xander.lab.bloghtml.infrastructure;

import com.xander.lab.auth.core.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Supplies the client IP used by the preview rate limiter after BlogHTML leaves core-host.
 * The extraction order intentionally matches the old core-host MVC adapter.
 */
@Component
public class BlogHtmlRequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UserContext.setClientIp(extractClientIp(request));
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (hasValue(ip)) {
            return ip.split(",", 2)[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        return hasValue(ip) ? ip : request.getRemoteAddr();
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value);
    }
}
