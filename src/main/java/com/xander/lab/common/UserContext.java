package com.xander.lab.common;

/**
 * 线程上下文：存储当前登录用户信息和客户端IP
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
