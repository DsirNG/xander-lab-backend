package com.xander.lab.context;

import com.xander.lab.core.api.ModelGateway;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.agent.infrastructure.AgentModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 容器装配冒烟测试：只加载模型客户端与其网关依赖，不碰数据库/Redis，
 * 用于在本地捕获「类中有多个构造器却未标注 @Autowired」之类的装配回归
 * （曾在生产启动时报 No default constructor found）。
 */
@SpringBootTest(classes = {
        JacksonAutoConfiguration.class,
        AgentModelProperties.class,
        AgentModelClient.class,
})
class ModelClientWiringTest {

    @MockBean
    private ModelGateway gateway;

    @Autowired
    private ApplicationContext context;

    @Test
    void agentModelClientIsWiredAsSingletonBean() {
        assertThat(context.getBean(AgentModelClient.class)).isNotNull();
    }
}
