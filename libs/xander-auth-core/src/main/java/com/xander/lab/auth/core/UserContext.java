package com.xander.lab.auth.core;

/**
 * 线程上下文：存储当前登录用户信息和客户端IP
 *
 * <p>Wave 0B 从 {@code com.xander.lab.identity.security} 移到本模块（V9 §20 的
 * 「trusted identity model」）。移动的原因不是好看：原来它挂在 <b>identity 这个业务域</b> 的包下，
 * 而它被 16 个域引用 —— 在跨域依赖清单里这就是十几条「跨域 implementation import」，
 * 拆分后每一条都会被当成需要改成网络调用的债。放进共享库，这些边就消失了。</p>
 *
 * <p><b>⚠️ ThreadLocal 不是 Servlet/Reactive 中立的。</b>本类没有任何框架依赖，所以从
 * <b>依赖</b> 角度它是中立的；但 ThreadLocal 只在「一线程一请求」的 Servlet 栈上成立。
 * WebFlux 下请求会在线程间迁移，本类会静默读到 null 或别的请求的身份。
 * Wave 1 的 Gateway 是 WebFlux，**不要**用它，要用 Reactor context 传递身份。
 * 这个约束记在这里而不是靠把类留在业务域包下来回避。</p>
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<String> IP_THREAD_LOCAL = new ThreadLocal<>();

    /**
     * 设置用户ID
     */
    public static void setUserId(Long userId) {
        USER_ID_THREAD_LOCAL.set(userId);
    }

    /**
     * 获取用户ID
     */
    public static Long getUserId() {
        return USER_ID_THREAD_LOCAL.get();
    }

    /**
     * 设置客户端IP地址
     */
    public static void setClientIp(String ip) {
        IP_THREAD_LOCAL.set(ip);
    }

    /**
     * 获取客户端IP地址
     */
    public static String getClientIp() {
        return IP_THREAD_LOCAL.get();
    }

    /**
     * 清除上下文
     */
    public static void clear() {
        USER_ID_THREAD_LOCAL.remove();
        IP_THREAD_LOCAL.remove();
    }
}
