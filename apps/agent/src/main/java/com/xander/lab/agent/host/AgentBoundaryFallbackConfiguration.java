package com.xander.lab.agent.host;

import com.xander.lab.blogagent.spi.BlogAgentImagePort;
import com.xander.lab.blogagent.spi.BlogAgentPort;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.core.api.AccessTokenLookupPort;
import com.xander.lab.core.api.ModelGateway;
import com.xander.lab.integration.api.CsdnBlogSyncPort;
import com.xander.lab.integration.api.JuejinBlogSyncPort;
import com.xander.lab.integration.api.OssPort;
import com.xander.lab.automation.spi.ContentPlanPort;
import com.xander.lab.automation.spi.EmailReminderPort;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import com.xander.lab.recitation.spi.RecitationQuizPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;

/**
 * Agent 在边界适配器尚未接入时的 fail-closed 装配。
 *
 * <p>这些 Bean 不是业务实现，也不吞掉调用：它们只允许新宿主先启动并暴露健康检查，
 * 真实调用会明确失败。后续的内部 HTTP 适配器以同一契约注册 Bean 后，
 * {@link ConditionalOnMissingBean} 会让这些兜底自动退出。</p>
 */
@Configuration(proxyBeanMethods = false)
public class AgentBoundaryFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModelGateway.class)
    ModelGateway modelGatewayFallback() {
        return ModelGateway.disabled();
    }

    @Bean
    @ConditionalOnMissingBean(AccessTokenLookupPort.class)
    AccessTokenLookupPort accessTokenLookupPortFallback() {
        return unavailable(AccessTokenLookupPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(BillingPort.class)
    BillingPort billingPortFallback() {
        return unavailable(BillingPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(ContentPostPort.class)
    ContentPostPort contentPostPortFallback() {
        return unavailable(ContentPostPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(MediaAssetPort.class)
    MediaAssetPort mediaAssetPortFallback() {
        return unavailable(MediaAssetPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(OssPort.class)
    OssPort ossPortFallback() {
        return unavailable(OssPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(CsdnBlogSyncPort.class)
    CsdnBlogSyncPort csdnBlogSyncPortFallback() {
        return unavailable(CsdnBlogSyncPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(JuejinBlogSyncPort.class)
    JuejinBlogSyncPort juejinBlogSyncPortFallback() {
        return unavailable(JuejinBlogSyncPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(ContentPlanPort.class)
    ContentPlanPort contentPlanPortFallback() {
        return unavailable(ContentPlanPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(EmailReminderPort.class)
    EmailReminderPort emailReminderPortFallback() {
        return unavailable(EmailReminderPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(BlogAgentPort.class)
    BlogAgentPort blogAgentPortFallback() {
        return unavailable(BlogAgentPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(BlogAgentImagePort.class)
    BlogAgentImagePort blogAgentImagePortFallback() {
        return unavailable(BlogAgentImagePort.class);
    }

    @Bean
    @ConditionalOnMissingBean(RecitationMaterialPort.class)
    RecitationMaterialPort recitationMaterialPortFallback() {
        return unavailable(RecitationMaterialPort.class);
    }

    @Bean
    @ConditionalOnMissingBean(RecitationQuizPort.class)
    RecitationQuizPort recitationQuizPortFallback() {
        return unavailable(RecitationQuizPort.class);
    }

    private static <T> T unavailable(Class<T> contract) {
        return contract.cast(Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "unavailable-" + contract.getSimpleName();
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    if (contract == AccessTokenLookupPort.class
                            && method.getName().equals("resolveUserId")) {
                        return null;
                    }
                    if (contract == BlogAgentImagePort.class
                            && method.getName().equals("isEnabled")) {
                        return false;
                    }
                    throw new IllegalStateException("Agent boundary is not configured: "
                            + contract.getSimpleName());
                }));
    }
}
