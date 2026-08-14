package com.xander.lab.integration.mcp.csdn.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnPublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void extractCoverImagesUsesFirstImage() throws Exception {
        Method method = CsdnPublisher.class.getDeclaredMethod("extractCoverImages", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        var covers = (java.util.List<String>) method.invoke(null, """
                <p>intro</p><img src="https://cdn.example.com/a.png"><img src="https://cdn.example.com/b.png">
                """);
        assertThat(covers).containsExactly("https://cdn.example.com/a.png");
    }

    @Test
    void isSessionExpiredRecognizesHttp401AndBodyCode401() throws Exception {
        JsonNode body401 = MAPPER.readTree("{\"code\":401,\"message\":\"请登录后操作！\"}");
        JsonNode ok = MAPPER.readTree("{\"code\":200,\"msg\":\"操作成功\"}");
        JsonNode failure = MAPPER.readTree("{\"code\":500,\"msg\":\"服务器错误\"}");

        assertThat(CsdnPublisher.isSessionExpired(401, null)).isTrue();
        assertThat(CsdnPublisher.isSessionExpired(200, body401)).isTrue();
        assertThat(CsdnPublisher.isSessionExpired(200, ok)).isFalse();
        assertThat(CsdnPublisher.isSessionExpired(200, failure)).isFalse();
        assertThat(CsdnPublisher.isSessionExpired(200, null)).isFalse();
        assertThat(CsdnPublisher.isSessionExpired(500, null)).isFalse();
    }

    @Test
    void normalizeDescriptionTrimsAndTruncatesToSharedMax() {
        assertThat(CsdnPublisher.normalizeDescription(null)).isEmpty();
        assertThat(CsdnPublisher.normalizeDescription("  摘要  ")).isEqualTo("摘要");
        String hundredChars = "a".repeat(100);
        assertThat(CsdnPublisher.normalizeDescription(hundredChars)).isEqualTo(hundredChars);
        assertThat(CsdnPublisher.normalizeDescription("a".repeat(150))).hasSize(100);
    }
}
