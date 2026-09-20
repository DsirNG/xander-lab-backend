package com.xander.lab.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayAccessPolicyTest {

    private final GatewayAccessPolicy policy = new GatewayAccessPolicy();

    @Test
    void keepsPublicPreviewCreationAndSandboxPageAnonymous() {
        assertThat(policy.decide(HttpMethod.POST, "/api/blog-html/previews").requiresToken()).isFalse();
        assertThat(policy.decide(HttpMethod.GET, "/blog-html/0123456789abcdef/raw").requiresToken()).isFalse();
    }

    @Test
    void protectsPrivateWritesAndAdminRoutes() {
        assertThat(policy.decide(HttpMethod.POST, "/api/blog/posts").access())
                .isEqualTo(GatewayAccessPolicy.Access.AUTHENTICATED);
        assertThat(policy.decide(HttpMethod.GET, "/api/admin/users").access())
                .isEqualTo(GatewayAccessPolicy.Access.ADMIN);
    }

    @Test
    void keepsWebsocketQueryTokenLocationExplicit() {
        GatewayAccessPolicy.Decision decision = policy.decide(HttpMethod.GET, "/ws/agent");
        assertThat(decision.requiresToken()).isTrue();
        assertThat(decision.tokenLocation()).isEqualTo("header_or_query");
    }
}
