package com.xander.lab.studio.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.admin.application.ModelGateway;
import com.xander.lab.admin.application.ModelGateway.ModelEndpoint;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.common.ModelCallResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口风格分流：img2three_vision 被配成 CHAT_COMPLETIONS 时，请求必须打到
 * /chat/completions，且 payload 换成该协议的 messages 形态。
 */
class Img2ThreeModelClientTest {

    private static final String SPEC_JSON = "{\"title\":\"小猫\",\"analysis\":{},\"spec\":{}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsToResponsesAndKeepsTheStructuredInputShapeByDefault() throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        startServer(paths, bodies, "/responses",
                "{\"output_text\":" + jsonString(SPEC_JSON) + "}");

        ModelCallResult result = client(ModelGateway.API_STYLE_RESPONSES)
                .reconstruct(new byte[]{1, 2, 3}, "image/png", "{}");

        assertThat(paths).containsExactly("/responses");
        assertThat(result.payload().path("title").asText()).isEqualTo("小猫");
        // Responses 形态用 instructions/input；这里同时钉住"默认不被误伤"。
        assertThat(bodies.get(0)).contains("\"instructions\"")
                .contains("\"input\"")
                .contains("input_image")
                .doesNotContain("\"messages\"");
    }

    /**
     * 配成 CHAT_COMPLETIONS 时必须真的换协议。
     *
     * <p>三个字段名都要跟着变，少一个供应商就会 400：instructions→messages[system]，
     * input→messages[user]，max_output_tokens→max_tokens，图片块
     * input_image→image_url。</p>
     */
    @Test
    void postsToChatCompletionsWithTranslatedPayloadWhenConfigured() throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        startServer(paths, bodies, "/chat/completions",
                "{\"choices\":[{\"message\":{\"content\":" + jsonString(SPEC_JSON) + "}}]}");

        ModelCallResult result = client(ModelGateway.API_STYLE_CHAT_COMPLETIONS)
                .reconstruct(new byte[]{1, 2, 3}, "image/png", "{}");

        assertThat(paths).containsExactly("/chat/completions");
        assertThat(result.payload().path("title").asText()).isEqualTo("小猫");

        String body = bodies.get(0);
        assertThat(body).contains("\"messages\"")
                .contains("\"system\"")
                .contains("\"image_url\"")
                .contains("\"max_tokens\"")
                .doesNotContain("\"instructions\"")
                .doesNotContain("\"input_image\"")
                .doesNotContain("\"max_output_tokens\"");
    }

    private Img2ThreeModelClient client(String apiStyle) {
        ModelEndpoint endpoint = new ModelEndpoint(6L, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "grok-4.5", apiStyle);
        return new Img2ThreeModelClient(new Img2ThreeProperties(), new BlogAgentProperties(),
                objectMapper, featureKey -> List.of(endpoint));
    }

    private void startServer(List<String> paths, List<String> bodies, String context, String responseBody)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(context, exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        // 另一条路径也注册上：若客户端发错了协议，测试会因为它没被命中而暴露出来
        // （paths 对不上），而不是变成一句语焉不详的连接失败。
        String other = "/responses".equals(context) ? "/chat/completions" : "/responses";
        server.createContext(other, exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    private String jsonString(String value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
