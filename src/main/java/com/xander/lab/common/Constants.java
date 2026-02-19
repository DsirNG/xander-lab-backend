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

    /** Redis Token 前缀: login:token:{userId} */
    String REDIS_TOKEN_PREFIX = "login:token:";

    /** Redis Token 黑名单前缀: login:blacklist:{token} */
    String REDIS_BLACKLIST_PREFIX = "login:blacklist:";

    /** Token 过期时间常量 */
    long CODE_EXPIRE_MINUTES = 5;
    
}
