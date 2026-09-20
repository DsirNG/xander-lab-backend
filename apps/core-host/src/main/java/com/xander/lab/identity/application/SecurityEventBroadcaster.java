package com.xander.lab.identity.application;

import com.xander.lab.identity.domain.SecurityEventOutboxEntry;

/**
 * 安全事件跨实例广播端口。
 *
 * <p>发件箱领取与重试仍由 {@link SecurityEventOutboxPublisher} 负责；这个端口只定义
 * "把已落库事件交给跨实例传输"。默认没有实现时，发布器退回本实例投递。</p>
 */
@FunctionalInterface
public interface SecurityEventBroadcaster {

    /** 广播一条已经提交到发件箱的安全事件。失败必须抛出运行时异常以触发发件箱退避。 */
    void publish(SecurityEventOutboxEntry entry);
}
