package com.xander.lab.common;

/**
 * 全局常量管理
 */
public interface Constants {

    /** Token 响应头 */
    String AUTHORIZATION_HEADER = "Authorization";

    /** Token 前缀 */
    String TOKEN_PREFIX = "Bearer ";

    /** Redis 验证码前缀: login:code:{email} */
    String REDIS_CODE_PREFIX = "login:code:";

    /** Redis Token 前缀: login:token:{token} → userId（支持多设备，每个 token 独立存储） */
    String REDIS_TOKEN_PREFIX = "login:token:";

    /** Redis 用户活跃 Token 集合: login:user_tokens:{userId} → Set<token> */
    String REDIS_USER_TOKENS_PREFIX = "login:user_tokens:";

    /** Redis Token 黑名单前缀: login:blacklist:{token}（暂未启用） */
    String REDIS_BLACKLIST_PREFIX = "login:blacklist:";

    /** Token 过期时间常量 */
    long CODE_EXPIRE_MINUTES = 5;
    
}
