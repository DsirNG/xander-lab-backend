package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentEvent;

/** Agent 会话事件的跨实例广播端口；数据库事件表仍是可重放的事实来源。 */
@FunctionalInterface
public interface AgentEventBroadcaster {

    /** 广播一条已经拥有持久化游标的事件；失败只影响快路径，不得改变已落库事实。 */
    void publish(AgentEvent event);
}
