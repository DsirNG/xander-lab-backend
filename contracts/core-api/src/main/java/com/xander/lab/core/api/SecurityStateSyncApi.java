package com.xander.lab.core.api;

import java.util.List;

/**
 * Gateway 与 core-host 之间的安全状态同步契约。
 *
 * <p>契约只描述跨部署单元传输的值，不携带 Spring、HTTP、MyBatis 或 Redis 依赖。
 * core-host 仍是 {@code account_security_state} 的唯一事实来源，Gateway 只维护
 * 自己实例的 deny cache 与 cursor。</p>
 */
public final class SecurityStateSyncApi {

    private SecurityStateSyncApi() {
    }

    public record Entry(long accountId, boolean disabled, long securityVersion, long changeSeq) {
    }

    public record Snapshot(List<Entry> states, long watermark) {
    }

    public record Changes(List<Entry> changes, long watermark) {
    }
}
