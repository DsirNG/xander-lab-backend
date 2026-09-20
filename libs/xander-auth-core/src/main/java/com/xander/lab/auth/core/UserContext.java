package com.xander.lab.auth.core;

/**
 * 线程上下文：存储当前登录用户信息和客户端IP
 *
 * <p><b>⚠️ ThreadLocal 不是 Servlet/Reactive 中立的。</b>本类没有任何框架依赖，所以从
 * <b>依赖</b> 角度它是中立的；但 ThreadLocal 只在「一线程一请求」的 Servlet 栈上成立。
 * WebFlux 下请求会在线程间迁移，本类会静默读到 null 或别的请求的身份，
 * 响应式消费方必须改用 Reactor context 传递身份。</p>
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
