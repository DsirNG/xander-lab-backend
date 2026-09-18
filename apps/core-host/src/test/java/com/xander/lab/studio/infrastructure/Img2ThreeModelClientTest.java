package com.xander.lab.studio.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.core.api.ModelGateway;
import com.xander.lab.core.api.ModelGateway.ModelEndpoint;
import com.xander.lab.common.ModelCallResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * 网关关闭时，端点必须**由 img2three 自己的配置拼出来**（baseUrl / apiKey / model 三个都取对）。
     *
     * <p>这是 {@code endpoints()} 的另一半：改造前它由三个 {@code resolveXxx()} 私有方法
     * 拼装（各自带一次对 blogagent 配置 bean 的回落），现在直接取 {@code properties}。
     * 上面两条用例的网关桩都返回非空端点，走的是早退分支，这一段<b>一直没被覆盖</b>。</p>
     */
    @Test
    void buildsTheEndpointFromImg2ThreeOwnSettingsWhenTheGatewayIsOff() throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        startServer(paths, bodies, "/responses", "{\"output_text\":" + jsonString(SPEC_JSON) + "}");

        Img2ThreeProperties properties = new Img2ThreeProperties();
        properties.getAgent().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getAgent().setApiKey("own-key");
        properties.getAgent().setModel("own-model");
        Img2ThreeModelClient client = new Img2ThreeModelClient(
                properties, objectMapper, featureKey -> List.of());

        client.reconstruct(new byte[]{1, 2, 3}, "image/png", "{}");

        assertThat(paths).containsExactly("/responses");
        assertThat(bodies.get(0)).contains("\"own-model\"");
    }

    /**
     * 网关关闭、且 img2three 与 blog.agent 都没配时，必须报**配置缺失**而不是别的失败。
     *
     * <p>这条路径改造前<b>一条测试都没有</b>（上面两条用例的网关桩都返回非空端点，
     * {@code endpoints()} 直接早退）。它是"回落链两端都空"的唯一出口，
     * 也是运维唯一会看到的提示 —— 文案里同时点名 IMG2THREE_AGENT_* 与 BLOG_AGENT_*，
     * 因为回落虽然搬到了 application.yml，可用的环境变量名没有变。</p>
     */
    @Test
    void reportsNotConfiguredWhenNeitherImg2ThreeNorTheBlogAgentFallbackIsSet() {
        Img2ThreeModelClient client = new Img2ThreeModelClient(
                new Img2ThreeProperties(), objectMapper, featureKey -> List.of());

        assertThatThrownBy(() -> client.reconstruct(new byte[]{1, 2, 3}, "image/png", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMG2THREE_AGENT_API_KEY")
                .hasMessageContaining("BLOG_AGENT_");
    }

    private Img2ThreeModelClient client(String apiStyle) {
        ModelEndpoint endpoint = new ModelEndpoint(6L, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "grok-4.5", apiStyle);
        return new Img2ThreeModelClient(new Img2ThreeProperties(),
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
