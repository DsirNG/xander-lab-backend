package com.xander.lab.identity.security;

import com.xander.lab.common.Constants;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.identity.application.AuthService;
import com.xander.lab.identity.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 认证拦截器
 * 验证请求头中的 JWT Token，并将用户ID存入 ThreadLocal
 * 同时提取客户端IP地址存入 UserContext
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final AuthService authService;

    /** 需要登录的受保护路径（写操作 + 私有读操作） */
    private static final List<String> PROTECTED_WRITE_PATTERNS = List.of(
            "POST:/api/blog/posts",
            "GET:/api/blog/posts/publish-status",
            "PUT:/api/blog/posts/*",
            "PATCH:/api/blog/posts/*",
            "DELETE:/api/blog/posts/*",
            "POST:/api/blog/media/images",
            "GET:/api/blog/media/images",
            // 智能体生成图片画廊属于用户私有素材，读取需要登录。
            "GET:/api/blog/media/images/agent",
            "POST:/api/blog-agent/tasks",
            "POST:/api/blog-agent/tasks/*",
            // Agent tasks contain private user material, so their reads are protected too.
            "GET:/api/blog-agent/tasks",
            "GET:/api/blog-agent/tasks/*",
            // Agent conversations contain private user material, so all access requires login.
            "POST:/api/agent/conversations",
            "GET:/api/agent/conversations",
            "GET:/api/agent/conversations/*",
            "POST:/api/agent/conversations/*",
            // 撤销分享链接同样属于私有写操作，需显式登记而非依赖兜底。
            "DELETE:/api/agent/conversations/*",
            // 个人知识、测验会话与录音任务全部属于当前用户，读写都必须显式鉴权。
            "GET:/api/recitations/*",
            "POST:/api/recitations/*",
            "PUT:/api/recitations/*",
            "DELETE:/api/recitations/*",
            "POST:/api/img2three/tasks",
            "POST:/api/img2three/tasks/*",
            "GET:/api/img2three/tasks",
            "GET:/api/img2three/tasks/*",
            "GET:/api/email-reminders",
            "POST:/api/email-reminders",
            "PATCH:/api/email-reminders/*",
            "DELETE:/api/email-reminders/*",
            "POST:/api/blog-plans",
            "GET:/api/blog-plans",
            "GET:/api/blog-plans/*",
            "PATCH:/api/blog-plans/*",
            "DELETE:/api/blog-plans/*",
            // AI 生成、主题队列、手动触发等写操作全部需要登录，显式登记而非依赖兜底。
            "POST:/api/blog-plans/*",
            "GET:/api/notifications",
            "GET:/api/notifications/*",
            "PATCH:/api/notifications/*",
            // 同步发布到 CSDN / 掘金属于私有写操作，显式登记（公开的浏览计数 /view 在其上方特判）。
            "POST:/api/blog/posts/*",
            "POST:/api/component/items",
            "POST:/api/studio/upload",
            "POST:/api/studio/save",
            // 文件上传涉及用户素材，写操作全部需要登录。
            "POST:/api/upload",
            "POST:/api/upload/*",
            // 工作流项目/画布属于用户私有内容，读与写都需要登录。
            "GET:/api/flow/projects",
            "GET:/api/flow/projects/*",
            "POST:/api/flow/projects",
            "PATCH:/api/flow/projects/*",
            "DELETE:/api/flow/projects/*",
            "PUT:/api/flow/canvases/*",
            // 画布快照属于用户私有内容，读操作同样需要登录。
            "GET:/api/flow/canvases/*",
            // CSDN 授权状态属于私有凭据信息，必须登录后才能读写。
            "GET:/api/publishing/csdn/authorization/status",
            "POST:/api/publishing/csdn/authorization/start",
            "POST:/api/publishing/csdn/authorization/cancel",
            "DELETE:/api/publishing/csdn/authorization",
            // 掘金授权状态属于私有凭据信息，必须登录后才能读写。
            "GET:/api/publishing/juejin/authorization/status",
            "POST:/api/publishing/juejin/authorization/start",
            "POST:/api/publishing/juejin/authorization/cancel",
            "DELETE:/api/publishing/juejin/authorization",
            // 积分余额与流水为私有数据，必须登录后才能读取。
            "GET:/api/points",
            "GET:/api/points/ledger",
            "GET:/api/points/prices",
            // 个人概览聚合用户、积分与文章数量，全部属于当前用户私有数据。
            "GET:/api/profile/overview"
    );

    /** 公开的读操作路径（支持通配符） */
    private static final List<String> GET_PUBLIC_PATTERNS = List.of(
            "GET:/api/blog/posts",
            "GET:/api/blog/posts/*",
            "GET:/api/blog/categories",
            "GET:/api/blog/tags",
            "GET:/api/blog/tags/popular",
            "GET:/api/blog/posts/recent",
            "GET:/api/components/menu",
            "GET:/api/components/*",
            "GET:/api/component/items",
            "GET:/api/component/items/*",
            "GET:/api/component/categories",
            "GET:/api/component/tags",
            "GET:/api/studio/preview/*",
            "GET:/api/studio/project/*",
            "GET:/api/agent/conversations/shared/*",
            // 本地存储文件以不可猜测的 UUID 文件名分享（博客配图、头像等需匿名展示），显式公开登记。
            "GET:/api/download/*",
            // 导出接口当前返回模拟数据，供前端 download() 演示，显式公开登记。
            "GET:/api/export/csv",
            "GET:/api/export/excel"
    );

    /** 完全排除的路径（独立鉴权体系，如 MCP 传输与 OAuth 端点） */
    private static final List<String> EXCLUDED_PATTERNS = List.of(
            "/api/auth/",
            "/api/mcp",
            "/api/studio/preview/",
            "/swagger-ui",
            "/v3/api-docs"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 提取并存储客户端IP（所有请求都执行，供后续业务使用）
        UserContext.setClientIp(extractClientIp(request));

        // 预检请求（OPTIONS）不携带凭据，由 CORS 配置决定放行；
        // 必须早于 /api/admin 等鉴权分支提前返回 2xx，否则真实请求会被拦截。
        if ("OPTIONS".equals(method)) {
            trySetUserContext(request);
            return true;
        }

        // 完全排除的路径直接放行
        for (String pattern : EXCLUDED_PATTERNS) {
            if (path.contains(pattern)) {
                return true;
            }
        }

        // 管理后台路径：必须登录且角色为 ADMIN，其余角色一律 403。需要在公开 GET 分支之前判断。
        if (path.startsWith("/api/admin/")) {
            return requireAdmin(request, response);
        }

        // 知识库文件中心整体属于当前用户的私有资料：文件夹、文件、正文、标签、上传会话没有一条是公开的。
        // 这里按前缀整段要求登录，而不是逐条登记路由——未匹配的 GET 默认公开，
        // 逐条登记一旦漏一条就会静默泄露某个用户的文件内容。
        if (path.startsWith("/api/knowledge-base/")) {
            return requireAuth(request, response);
        }

        // 公开的GET读操作直接放行（但仍尝试解析token以便记录用户信息）
        // This matches the public post-detail wildcard below, but idempotent
        // publish status belongs to the authenticated user only.
        if ("GET".equals(method) && "/api/blog/posts/publish-status".equals(path)) {
            return requireAuth(request, response);
        }
        if ("GET".equals(method) && (path.equals("/api/blog/posts/mine") || path.startsWith("/api/blog/posts/mine/"))) {
            return requireAuth(request, response);
        }

        // 公开的写操作：文章浏览计数、组件分享，均无需登录。
        if ("POST".equals(method) && path.startsWith("/api/blog/posts/") && path.endsWith("/view")) {
            trySetUserContext(request);
            return true;
        }
        if ("POST".equals(method) && "/api/components/share".equals(path)) {
            trySetUserContext(request);
            return true;
        }
        if ("POST".equals(method) && "/api/blog-html/previews".equals(path)) {
            // 匿名 HTML 预览会话创建：无需登录，由服务端按 IP 限流。
            trySetUserContext(request);
            return true;
        }

        for (String pattern : GET_PUBLIC_PATTERNS) {
            String[] parts = pattern.split(":", 2);
            if (parts[0].equals(method) && matchPath(parts[1], path)) {
                // 尝试解析token，设置用户上下文（不阻断请求）
                trySetUserContext(request);
                return true;
            }
        }

        // 需要登录的写操作
        for (String pattern : PROTECTED_WRITE_PATTERNS) {
            String[] parts = pattern.split(":", 2);
            if (parts[0].equals(method) && matchPath(parts[1], path)) {
                return requireAuth(request, response);
            }
        }

        // 未匹配的路径：只读方法（GET/HEAD/OPTIONS）保持公开；其他写方法默认要求登录，
        // 避免新增私有接口因忘记登记而静默公开。
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            trySetUserContext(request);
            return true;
        }
        trySetUserContext(request);
        return requireAuth(request, response);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 尝试解析请求中的token并设置用户上下文
     * 不抛出异常，解析失败则上下文为空
     */
    private void trySetUserContext(HttpServletRequest request) {
        String authHeader = request.getHeader(Constants.AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(Constants.TOKEN_PREFIX)) {
            String token = authHeader.substring(Constants.TOKEN_PREFIX.length());
            try {
                if (authService.validateAccessToken(token)) {
                    String userId = jwtUtil.getSubject(token);
                    UserContext.setUserId(Long.parseLong(userId));
                }
            } catch (Exception e) {
                // 解析失败不阻断请求
                log.debug("[Auth] Token 解析失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 强制要求认证
     */
    private boolean requireAuth(HttpServletRequest request, HttpServletResponse response) {
        String authHeader = request.getHeader(Constants.AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(Constants.TOKEN_PREFIX)) {
            unauthorized(response);
            return false;
        }

        String token = authHeader.substring(Constants.TOKEN_PREFIX.length());
        if (!authService.validateAccessToken(token)) {
            unauthorized(response);
            return false;
        }

        String userId = jwtUtil.getSubject(token);
        UserContext.setUserId(Long.parseLong(userId));
        return true;
    }

    /**
     * 管理后台鉴权：先走普通登录校验，再校验 JWT 内携带的 role 必须为 ADMIN。
     * 身份模块拥有 JWT 解析，这里是唯一授权点；不做权限升级或用户态写入。
     */
    private boolean requireAdmin(HttpServletRequest request, HttpServletResponse response) {
        if (!requireAuth(request, response)) {
            return false;
        }
        String authHeader = request.getHeader(Constants.AUTHORIZATION_HEADER);
        String token = authHeader.substring(Constants.TOKEN_PREFIX.length());
        try {
            if (!"ADMIN".equals(jwtUtil.parseToken(token).get("role", String.class))) {
                forbidden(response);
                return false;
            }
        } catch (Exception e) {
            // token 过期/签名变化等：按未登录处理，避免 403 泄露角色信息。
            unauthorized(response);
            return false;
        }
        return true;
    }

    /**
     * 简单路径匹配（支持单层通配符 *）
     */
    private boolean matchPath(String pattern, String path) {
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix + "/") || path.equals(prefix);
        }
        return pattern.equals(path);
    }

    /**
     * 从请求头中提取客户端真实IP
     * 优先读取代理透传头（X-Forwarded-For / X-Real-IP），回退到 remoteAddr
     *
     * @param request HTTP请求
     * @return 客户端IP地址
     */
    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能包含多个IP，取第一个（真实客户端IP）
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    private void unauthorized(HttpServletResponse response) {
        try {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
        } catch (Exception e) {
            log.error("[Auth] 响应写入失败", e);
        }
    }

    private void forbidden(HttpServletResponse response) {
        try {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"code\":403,\"message\":\"无管理员权限\",\"data\":null}");
        } catch (Exception e) {
            log.error("[Auth] 响应写入失败", e);
        }
    }
}
