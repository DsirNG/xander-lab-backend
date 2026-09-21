package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ContentMailClientTest {

    @Test
    void forwardsRenderedMailAcrossTheInternalBoundary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/automation/mail/simple"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DinQor-Internal-Token", "content-token"))
                .andExpect(content().json("{\"to\":\"demo@example.com\",\"subject\":\"Subject\",\"content\":\"body\"}"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://core-host:30002/internal/automation/mail/html"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DinQor-Internal-Token", "content-token"))
                .andExpect(content().json("{\"to\":\"demo@example.com\",\"subject\":\"Subject\",\"plainText\":\"plain\",\"html\":\"<p>html</p>\"}"))
                .andRespond(withSuccess());

        ContentMailClient client = new ContentMailClient(builder.build(), properties());
        client.sendSimpleMail("demo@example.com", "Subject", "body");
        client.sendHtmlMail("demo@example.com", "Subject", "plain", "<p>html</p>");

        server.verify();
    }

    private ContentAutomationProperties properties() {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setCoreBaseUrl("http://core-host:30002");
        properties.setCoreInternalToken("content-token");
        return properties;
    }
}
