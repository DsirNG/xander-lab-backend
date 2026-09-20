package com.xander.lab.webmvc;

import com.xander.lab.common.Constants;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.auth.core.JwtUtil;
import com.xander.lab.core.api.AccessTokenLookupPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * MVC 认证适配器（V9 §20 的 "MVC Auth Adapter"）。
 *
 * <p>验证请求头中的 access token，并把用户 id 放进 {@link UserContext}；同时把客户端 IP
 * 也放进去。三条出口：公开路径放行、受保护路径 401、{@code /api/admin/**} 非 ADMIN 角色 403。</p>
 *
 * <h2>为什么它依赖 {@link AccessTokenLookupPort} 而不是 {@code identity.application.AuthService}</h2>
 *
 * <p>这个类原来注入 identity 域的 {@code AuthService}，只用了它的两个动作：
 * {@code validateAccessToken(token)} 与 {@code jwtUtil.getSubject(token)}。这两步合起来
 * 就是 {@link AccessTokenLookupPort#resolveUserId(String)} —— 而那个端口是第十四刀**专门为
 * 这个能力**建的（当时给 WebSocket 握手用，注释里还写着"握手路径不在 {@code /api/**} 下，
 * 走不到 AuthInterceptor"）。</p>
 *
 * <p>不这么改就没法搬进库：库不能依赖业务域，否则它就不是"每个 MVC 服务都能用的支撑"，
 * 而是"identity 域的一部分"。判据不是"看起来像契约"，是 ADR-2：跨域走**消费方拥有的契约**。</p>
 *
 * <p>⚠️ **一处行为变化，方向是 fail-closed**：token 的 subject 不是数字时，旧写法
 * {@code Long.parseLong(jwtUtil.getSubject(token))} 抛 {@code NumberFormatException} ⇒ 500；
 * 现在由提供方解析，解析失败返回 {@code null} ⇒ 401。两种都是拒绝请求，但 401 才是这条路径
 * 该有的答案（且不再向日志里丢一条 500 堆栈）。</p>
 *
 * <p>⚠️ 另一处口径变化：{@code AuthService} 是 identity 域里**唯一**的令牌真相源，现在改成
 * 端口。所以"access token 怎么算有效"这件事从"本类调用了一个域服务"变成"本类依赖一个契约"，
 * 该契约的实现仍在 identity 域内（{@code AuthService.resolveUserId}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 只用于 {@code /api/admin/**} 的 role 断言；令牌有效性判定已经全部走端口。 */
    private final JwtUtil jwtUtil;
    private final AccessTokenLookupPort accessTokenLookupPort;

    /** 需要登录的受保护路径（写操作 + 私有读操作） */
    private static final List<String> PROTECTED_WRITE_PATTERNS = List.of(
            // 虚拟支付商品目录公开，但订单创建、对账和私有订单状态必须登录。
            "POST:/api/virtual-pay/orders",
            "POST:/api/virtual-pay/orders/*",
            "GET:/api/virtual-pay/orders/*",
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
            // 用户技能库与远端 MCP 服务器配置都是「自己名下」的私有资料：
            // 控制器注释明确要求登录（AgentSkillController「Authenticated API」、
            // AgentMcpServerController「用户自己的远端 MCP 服务器配置」），
            // 但此前没有登记 → 落到「未匹配的 GET 默认公开」兜底，变成匿名可读。
            // 归属过滤在服务层，鉴权必须在这里，两者缺一不可。
            "GET:/api/agent/skills",
            "GET:/api/agent/skills/*",
            "GET:/api/agent/mcp-servers",
            "GET:/api/agent/mcp-servers/*",
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
            "/api/virtual-pay/notify",
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
     * 尝试解析请求中的 token 并设置用户上下文。
     * 不抛异常：拿不到身份就按匿名处理。
     */
    private void trySetUserContext(HttpServletRequest request) {
        String authHeader = request.getHeader(Constants.AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(Constants.TOKEN_PREFIX)) {
            return;
        }
        String token = authHeader.substring(Constants.TOKEN_PREFIX.length());
        Long userId = accessTokenLookupPort.resolveUserId(token);
        if (userId == null) {
            // 令牌缺失 / 过期 / 已登出 / subject 解析不了 —— 一律按匿名，不阻断公开请求。
            log.debug("[Auth] access token 未通过校验，按匿名处理");
            return;
        }
        UserContext.setUserId(userId);
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
        Long userId = accessTokenLookupPort.resolveUserId(token);
        if (userId == null) {
            unauthorized(response);
            return false;
        }
        UserContext.setUserId(userId);
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
