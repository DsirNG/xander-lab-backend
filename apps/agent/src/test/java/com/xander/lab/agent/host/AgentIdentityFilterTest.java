package com.xander.lab.agent.host;

import com.xander.lab.auth.core.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentIdentityFilterTest {

    private final AgentIdentityFilter filter = new AgentIdentityFilter();

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void restoresGatewayIdentityForControllerAndClearsItAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/conversations");
        request.addHeader(AgentIdentityFilter.USER_ID_HEADER, "42");
        AtomicReference<Long> observed = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> observed.set(UserContext.getUserId()));

        assertThat(observed).hasValue(42L);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void rejectsMissingOrMalformedIdentityByLeavingContextAnonymous() throws Exception {
        UserContext.setUserId(99L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/conversations");
        request.addHeader(AgentIdentityFilter.USER_ID_HEADER, "not-a-user");
        AtomicReference<Long> observed = new AtomicReference<>(99L);

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> observed.set(UserContext.getUserId()));

        assertThat(observed).hasValue(null);
        assertThat(UserContext.getUserId()).isNull();
    }
}
