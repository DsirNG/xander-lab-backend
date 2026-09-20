package com.xander.lab.context;

import com.xander.lab.core.api.ModelGateway;
import com.xander.lab.admin.infrastructure.FeatureModelConfigMapper;
import com.xander.lab.admin.infrastructure.ModelGatewayProperties;
import com.xander.lab.admin.infrastructure.ModelProviderMapper;
import com.xander.lab.admin.infrastructure.ProviderKeyCipher;
import com.xander.lab.admin.application.ModelGatewayService;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.agent.infrastructure.AgentModelProperties;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentModelClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.studio.infrastructure.Img2ThreeModelClient;
import com.xander.lab.studio.infrastructure.Img2ThreeProperties;
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
        ModelGatewayProperties.class,
        ProviderKeyCipher.class,
        ModelGatewayService.class,
        AgentModelProperties.class,
        BlogAgentProperties.class,
        Img2ThreeProperties.class,
        AgentModelClient.class,
        BlogAgentModelClient.class,
        BlogAgentImageClient.class,
        Img2ThreeModelClient.class,
})
class ModelClientWiringTest {

    @MockBean
    private FeatureModelConfigMapper configMapper;
    @MockBean
    private ModelProviderMapper providerMapper;

    @Autowired
    private ApplicationContext context;

    @Test
    void modelClientsAndGatewayAreWiredAsSingletonBeans() {
        assertThat(context.getBean(AgentModelClient.class)).isNotNull();
        assertThat(context.getBean(BlogAgentModelClient.class)).isNotNull();
        assertThat(context.getBean(BlogAgentImageClient.class)).isNotNull();
        assertThat(context.getBean(Img2ThreeModelClient.class)).isNotNull();
        assertThat(context.getBean(ModelGateway.class)).isNotNull();
        assertThat(context.getBean(ProviderKeyCipher.class)).isNotNull();
    }
}