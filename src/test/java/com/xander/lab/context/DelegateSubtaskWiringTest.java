package com.xander.lab.context;

import com.xander.lab.agent.application.AgentToolRegistry;
import com.xander.lab.agent.application.DelegateSubtaskTool;
import com.xander.lab.agent.application.SubAgentRunner;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 派发工具的装配冒烟测试。
 *
 * <p>{@link DelegateSubtaskTool} 是一个 AgentTool，会被 {@link AgentToolRegistry} 收集，
 * 而它背后的 {@link SubAgentRunner} 又要用到那张工具表——这是一个天然的构造期循环，
 * 只在容器启动时暴露。这里用最小上下文把它钉住，避免以后有人把 ObjectProvider 改成直接注入。</p>
 */
@SpringBootTest(classes = {
        AgentConversationProperties.class,
        AgentToolRegistry.class,
        SubAgentRunner.class,
        DelegateSubtaskTool.class,
})
class DelegateSubtaskWiringTest {

    @MockBean
    private AgentModelClient modelClient;

    @Autowired
    private AgentToolRegistry registry;
    @Autowired
    private DelegateSubtaskTool tool;

    @Test
    void theDelegationToolAndItsRunnerWireWithoutACircularDependency() {
        assertThat(registry.get(DelegateSubtaskTool.NAME)).isSameAs(tool);
        assertThat(registry.allowedNames()).contains(DelegateSubtaskTool.NAME);
    }
}
