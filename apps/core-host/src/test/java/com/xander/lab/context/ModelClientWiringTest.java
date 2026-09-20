package com.xander.lab.context;

import com.xander.lab.admin.application.ModelGatewayService;
import com.xander.lab.admin.infrastructure.FeatureModelConfigMapper;
import com.xander.lab.admin.infrastructure.ModelGatewayProperties;
import com.xander.lab.admin.infrastructure.ModelProviderMapper;
import com.xander.lab.admin.infrastructure.ProviderKeyCipher;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentModelClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.core.api.ModelGateway;
import com.xander.lab.studio.infrastructure.Img2ThreeModelClient;
import com.xander.lab.studio.infrastructure.Img2ThreeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring 容器装配冒烟测试：只加载 Core 模型客户端与网关依赖。 */
@SpringBootTest(classes = {
        JacksonAutoConfiguration.class,
        ModelGatewayProperties.class,
        ProviderKeyCipher.class,
        ModelGatewayService.class,
        BlogAgentProperties.class,
        Img2ThreeProperties.class,
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
    void coreModelClientsAndGatewayAreWiredAsSingletonBeans() {
        assertThat(context.getBean(BlogAgentModelClient.class)).isNotNull();
        assertThat(context.getBean(BlogAgentImageClient.class)).isNotNull();
        assertThat(context.getBean(Img2ThreeModelClient.class)).isNotNull();
        assertThat(context.getBean(ModelGateway.class)).isNotNull();
        assertThat(context.getBean(ProviderKeyCipher.class)).isNotNull();
        assertThat(context.getBean(ObjectMapper.class)).isNotNull();
    }
}
