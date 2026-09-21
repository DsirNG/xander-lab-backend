package com.xander.lab.core.api;

/**
 * Core 安全事件 Redis Pub/Sub 的跨部署单元契约。
 *
 * <p>该类型只描述稳定的 JSON 字段和持久化事件类型，不携带 Redis、Jackson、Spring
 * 或 core-host 的领域对象。发布方与 Gateway 订阅方必须共同依赖它，避免两边各自维护
 * 一份事件字面量而静默失配。</p>
 */
public final class SecurityEventPubSubApi {

    /** 账号被禁用的发件箱事件类型。该值会随事件 JSON 持久/传播，不能随意改名。 */
    public static final String ACCOUNT_DISABLED = "AccountDisabled";
    /** 账号被启用的发件箱事件类型。该值会随事件 JSON 持久/传播，不能随意改名。 */
    public static final String ACCOUNT_ENABLED = "AccountEnabled";
    /** Refresh Token 重用事件类型；安全平面订阅者收到后无需改变 deny cache。 */
    public static final String REFRESH_TOKEN_REUSE = "RefreshTokenReuse";

    private SecurityEventPubSubApi() {
    }

    /**
     * Redis 频道上的稳定消息形状。
     *
     * <p>{@code payload} 保留 Core 发件箱的 JSON 字符串，订阅方按自己的缓存模型解析，
     * 不把 Core 的数据库实体泄露到跨服务契约。</p>
     */
    public record Message(Long id,
                          String aggregateType,
                          String aggregateId,
                          String eventType,
                          String payload) {
    }
}
