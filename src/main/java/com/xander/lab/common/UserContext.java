package com.xander.lab.common;

/**
 * 线程上下文：存储当前登录用户信息（主要为用户ID）
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID_THREAD_LOCAL = new ThreadLocal<>();

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
     * 清除上下文
     */
    public static void clear() {
        USER_ID_THREAD_LOCAL.remove();
    }
}
