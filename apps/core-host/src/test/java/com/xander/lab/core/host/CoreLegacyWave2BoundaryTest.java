package com.xander.lab.core.host;

import com.xander.lab.automation.api.BlogNotificationController;
import com.xander.lab.blogagent.api.BlogAgentController;
import com.xander.lab.integration.mcp.csdn.api.CsdnAuthorizationController;
import com.xander.lab.integration.mcp.csdn.api.CsdnBlogSyncController;
import com.xander.lab.integration.mcp.juejin.api.JuejinAuthorizationController;
import com.xander.lab.integration.mcp.juejin.api.JuejinBlogSyncController;
import com.xander.lab.integration.mcp.juejin.api.JuejinCatalogController;
import com.xander.lab.publishing.api.BlogController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the Core-side switches used by the Wave 2 route/drain sequence. */
class CoreLegacyWave2BoundaryTest {

    @Test
    void extractedPublicHandlersHaveExplicitWave2DisableSwitches() {
        assertThat(property(BlogController.class))
                .containsExactly("core.legacy", "publishing-create-enabled");
        assertThat(property(BlogAgentController.class))
                .containsExactly("core.legacy", "blogagent-stream-enabled");
        assertThat(property(BlogNotificationController.class))
                .containsExactly("core.legacy", "notification-stream-enabled");
        for (Class<?> type : List.of(
                CsdnAuthorizationController.class,
                CsdnBlogSyncController.class,
                JuejinAuthorizationController.class,
                JuejinBlogSyncController.class,
                JuejinCatalogController.class)) {
            assertThat(property(type))
                    .containsExactly("core.legacy", "platform-public-enabled");
        }
    }

    @Test
    void everySwitchDefaultsToCompatibilityMode() {
        for (Class<?> type : List.of(
                BlogController.class,
                BlogAgentController.class,
                BlogNotificationController.class,
                CsdnAuthorizationController.class,
                CsdnBlogSyncController.class,
                JuejinAuthorizationController.class,
                JuejinBlogSyncController.class,
                JuejinCatalogController.class)) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition.matchIfMissing())
                    .as(type.getSimpleName() + " must preserve the legacy default")
                    .isTrue();
            assertThat(condition.havingValue()).isEqualTo("true");
        }
    }

    private String[] property(Class<?> type) {
        ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).as(type.getSimpleName() + " needs a Wave 2 switch").isNotNull();
        assertThat(condition.prefix()).isEqualTo("core.legacy");
        assertThat(condition.name()).hasSize(1);
        return new String[]{condition.prefix(), condition.name()[0]};
    }
}
