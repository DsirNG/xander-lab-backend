package com.xander.lab.gateway;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway 每实例的账号封禁缓存。
 *
 * <p>它只保存事件传播到本实例后的结果，不是安全状态 SOT。跨重启的快照/增量对账
 * 必须由后续 Gateway reconciler 提供；缓存缺失时不会伪装成已完成同步。</p>
 */
@Component
public class GatewaySecurityStateCache {

    private final Map<Long, State> states = new ConcurrentHashMap<>();

    public boolean isDisabled(long accountId) {
        State state = states.get(accountId);
        return state != null && state.disabled();
    }

    /** 版本单调地应用封禁/解封事件，重复或迟到事件无副作用。 */
    public void apply(long accountId, boolean disabled, long securityVersion) {
        states.compute(accountId, (ignored, current) -> {
            if (current != null && current.securityVersion() >= securityVersion) {
                return current;
            }
            return new State(disabled, securityVersion);
        });
    }

    public int disabledCount() {
        int count = 0;
        for (State state : states.values()) {
            if (state.disabled()) {
                count++;
            }
        }
        return count;
    }

    public record State(boolean disabled, long securityVersion) {
    }
}
