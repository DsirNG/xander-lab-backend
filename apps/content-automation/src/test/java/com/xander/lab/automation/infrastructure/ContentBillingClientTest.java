package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ContentBillingClientTest {

    @Test
    void forwardsOnlyFlatBillingOperations() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/automation/billing/flat-points"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DinQor-Internal-Token", "content-token"))
                .andExpect(content().json("{\"bizType\":\"EMAIL_REMINDER\"}"))
                .andRespond(withSuccess("{\"points\":5000}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://core-host:30002/internal/automation/billing/charge-flat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DinQor-Internal-Token", "content-token"))
                .andExpect(content().json("{\"userId\":7,\"bizType\":\"EMAIL_REMINDER\",\"bizKey\":\"email:7:1\",\"bizRef\":\"task:7\",\"points\":5000,\"remark\":\"sent\"}"))
                .andRespond(withSuccess());

        ContentBillingClient client = new ContentBillingClient(builder.build(), properties());
        assertThat(client.flatPoints("EMAIL_REMINDER")).isEqualTo(5000L);
        client.chargeFlat(7L, "EMAIL_REMINDER", "email:7:1", "task:7", 5000L, "sent");

        server.verify();
    }

    private ContentAutomationProperties properties() {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setCoreBaseUrl("http://core-host:30002");
        properties.setCoreInternalToken("content-token");
        return properties;
    }
}
