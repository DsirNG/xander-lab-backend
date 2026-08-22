package com.xander.lab.blogagent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.admin.application.ModelGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Security-boundary tests for model-provided image download URLs and bodies. */
class BlogAgentImageClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void clampsNonPositiveTimeouts() {
        assertThat(BlogAgentImageClient.positiveTimeoutMillis(0)).isEqualTo(1);
        assertThat(BlogAgentImageClient.positiveTimeoutMillis(-500)).isEqualTo(1);
        assertThat(BlogAgentImageClient.positiveTimeoutMillis(2500)).isEqualTo(2500);
    }

    @Test
    void summarizesFailuresForLoggingWithoutRawBodies() {
        assertThat(BlogAgentImageClient.failureReason(new IllegalStateException(
                "400 Bad Request: [{\"error\":{\"message\":\"invalid size\"}}]\nsecond line")))
                .isEqualTo("HTTP 400");
        HttpClientErrorException providerError = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                org.springframework.http.HttpHeaders.EMPTY,
                ("{\"error\":{\"code\":\"invalid_value\",\"type\":\"invalid_request_error\","
                        + "\"param\":\"size\",\"message\":\"Invalid size; key-token-1234567890 must stay hidden\"}}")
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        assertThat(BlogAgentImageClient.failureReason(providerError))
                .isEqualTo("HTTP 400 code=invalid_value type=invalid_request_error param=size "
                        + "message=Invalid size; [REDACTED] must stay hidden");
        assertThat(BlogAgentImageClient.failureReason(new IllegalStateException((String) null)))
                .isEqualTo("IllegalStateException");
        String longMessage = "x".repeat(500);
        assertThat(BlogAgentImageClient.failureReason(new IllegalStateException(longMessage)))
                .hasSize(303)
                .endsWith("...");
    }

    @Test
    void preservesImageResponseStructureWhileRedactingBinaryAndSignedUrls() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String binary = "aGVsbG8=";
        JsonNode original = mapper.readTree("""
                {"created":123,"data":[{"b64_json":"%s","url":"https://cdn.example/image.png?token=secret#part"}],
                 "choices":[{"message":{"content":"data:image/png;base64,%s"}}]}
                """.formatted(binary, binary));

        JsonNode sanitized = BlogAgentImageClient.responseForLogging(original);

        assertThat(sanitized.path("created").asInt()).isEqualTo(123);
        assertThat(sanitized.path("data").path(0).path("b64_json").asText())
                .isEqualTo("[REDACTED image bytes length=8]");
        assertThat(sanitized.path("data").path(0).path("url").asText())
                .isEqualTo("https://cdn.example/image.png?[REDACTED]");
        assertThat(sanitized.path("choices").path(0).path("message").path("content").asText())
                .isEqualTo("data:image/png;base64,[REDACTED image bytes length=8]");
        assertThat(original.path("data").path(0).path("b64_json").asText()).isEqualTo(binary);
    }

    @Test
    void acceptsOnlyCredentialFreeHttpsUrlsWhoseEveryAddressIsPublic() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});

        URI accepted = BlogAgentImageClient.validateDownloadUri(
                "https://images.example.test/result.png", ignored -> new InetAddress[]{publicAddress});

        assertThat(accepted.getScheme()).isEqualTo("https");
        assertThatThrownBy(() -> BlogAgentImageClient.validateDownloadUri(
                "http://images.example.test/result.png", ignored -> new InetAddress[]{publicAddress}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> BlogAgentImageClient.validateDownloadUri(
                "https://user:secret@images.example.test/result.png", ignored -> new InetAddress[]{publicAddress}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsEveryBlockedAddressAndMixedDnsAnswers() throws Exception {
        List<InetAddress> blocked = List.of(
                InetAddress.getByAddress(new byte[]{0, 0, 0, 0}),
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
                InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, 1, 1}),
                InetAddress.getByAddress(new byte[]{10, 0, 0, 1}),
                InetAddress.getByAddress(new byte[]{(byte) 224, 0, 0, 1}),
                InetAddress.getByAddress(new byte[]{(byte) 100, 64, 0, 1}),
                InetAddress.getByAddress(new byte[]{(byte) 198, 18, 0, 1}),
                InetAddress.getByAddress(new byte[]{
                        (byte) 0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}));

        for (InetAddress address : blocked) {
            assertThatThrownBy(() -> BlogAgentImageClient.validateDownloadUri(
                    "https://images.example.test/result.png", ignored -> new InetAddress[]{address}))
                    .as("address %s", address.getHostAddress())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非公网");
        }

        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 2});
        assertThatThrownBy(() -> BlogAgentImageClient.validateDownloadUri(
                "https://images.example.test/result.png",
                ignored -> new InetAddress[]{publicAddress, privateAddress}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非公网");
    }

    @Test
    void rejectsNonImageAndRedirectResponses() {
        HttpHeaders textHeaders = headers(Map.of("Content-Type", "text/html"));
        assertThatThrownBy(() -> BlogAgentImageClient.readDownloadedImage(
                200, textHeaders, new ByteArrayInputStream("not an image".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是图片类型");

        HttpHeaders redirectHeaders = headers(Map.of(
                "Content-Type", "image/png",
                "Location", "https://other.example.test/image.png"));
        assertThatThrownBy(() -> BlogAgentImageClient.readDownloadedImage(
                302, redirectHeaders, new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 302");
    }

    @Test
    void rejectsDeclaredAndStreamingBodiesAboveTenMegabytes() {
        HttpHeaders declaredTooLarge = headers(Map.of(
                "Content-Type", "image/png",
                "Content-Length", Integer.toString(BlogAgentImageClient.MAX_IMAGE_BYTES + 1)));
        assertThatThrownBy(() -> BlogAgentImageClient.readDownloadedImage(
                200, declaredTooLarge, new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超过 10MB");

        HttpHeaders chunkedImage = headers(Map.of("Content-Type", "image/png"));
        InputStream oversizedStream = new RepeatingInputStream(BlogAgentImageClient.MAX_IMAGE_BYTES + 1L);
        assertThatThrownBy(() -> BlogAgentImageClient.readDownloadedImage(
                200, chunkedImage, oversizedStream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超过 10MB");
    }

    @Test
    void readsAValidBoundedImageResponse() {
        byte[] bytes = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        HttpHeaders headers = headers(Map.of(
                "Content-Type", "image/png; charset=binary",
                "Content-Length", Integer.toString(bytes.length)));

        BlogAgentImageClient.GeneratedImage image = BlogAgentImageClient.readDownloadedImage(
                200, headers, new ByteArrayInputStream(bytes));

        assertThat(image.bytes()).containsExactly(bytes);
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.extension()).isEqualTo("png");
    }

    @Test
    void chatCompletionsStyleDecodesDataUrlImage() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        server = startServer("/chat/completions",
                "{\"choices\":[{\"message\":{\"content\":["
                        + "{\"type\":\"output_text\",\"text\":\"ok\"},"
                        + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + dataUrl + "\"}}"
                        + "]}}]}");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        BlogAgentImageClient client = new BlogAgentImageClient(properties(baseUrl),
                new ObjectMapper(),
                feature -> List.of(new ModelGateway.ModelEndpoint(1L, baseUrl, "test-key",
                        "gpt-image-2", ModelGateway.API_STYLE_CHAT_COMPLETIONS)));

        BlogAgentImageClient.GeneratedImage image = client.generate("draw a robot");

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.extension()).isEqualTo("png");
        assertThat(image.bytes()).containsExactly(png);
    }

    @Test
    void chatCompletionsStyleRejectsNonImageDataUrl() throws Exception {
        server = startServer("/chat/completions",
                "{\"choices\":[{\"message\":{\"content\":["
                        + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:text/html;base64,PGI%2B\"}}"
                        + "]}}]}");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        BlogAgentImageClient client = new BlogAgentImageClient(properties(baseUrl),
                new ObjectMapper(),
                feature -> List.of(new ModelGateway.ModelEndpoint(1L, baseUrl, "test-key",
                        "gpt-image-2", ModelGateway.API_STYLE_CHAT_COMPLETIONS)));

        assertThatThrownBy(() -> client.generate("draw a robot"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是图片类型");
    }

    @Test
    void chatCompletionsStyleThrowsWhenNoImagePartReturned() throws Exception {
        server = startServer("/chat/completions",
                "{\"choices\":[{\"message\":{\"content\":\"only text\"}}]}");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        BlogAgentImageClient client = new BlogAgentImageClient(properties(baseUrl),
                new ObjectMapper(),
                feature -> List.of(new ModelGateway.ModelEndpoint(1L, baseUrl, "test-key",
                        "gpt-image-2", ModelGateway.API_STYLE_CHAT_COMPLETIONS)));

        assertThatThrownBy(() -> client.generate("draw a robot"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有返回可读取的图片");
    }

    @Test
    void environmentFallbackDefaultsToChatCompletionsStyle() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 9, 9};
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        server = startServer("/chat/completions",
                "{\"choices\":[{\"message\":{\"content\":["
                        + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + dataUrl + "\"}}"
                        + "]}}]}");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        BlogAgentImageClient client = new BlogAgentImageClient(properties(baseUrl), new ObjectMapper());

        BlogAgentImageClient.GeneratedImage image = client.generate("draw a robot");

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.extension()).isEqualTo("png");
        assertThat(image.bytes()).containsExactly(png);
    }

    @Test
    void imagesGenerationsStyleDecodesBase64WhenConfiguredExplicitly() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 9, 9};
        server = startServer("/images/generations",
                "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        BlogAgentImageClient client = new BlogAgentImageClient(properties(baseUrl),
                new ObjectMapper(),
                feature -> List.of(new ModelGateway.ModelEndpoint(1L, baseUrl, "test-key",
                        "dall-e-3", ModelGateway.API_STYLE_IMAGES_GENERATIONS)));

        BlogAgentImageClient.GeneratedImage image = client.generate("draw a robot");

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.extension()).isEqualTo("png");
        assertThat(image.bytes()).containsExactly(png);
    }

    @Test
    void chatPromptStyleSendsTopLevelPromptAndReadsUrlContent() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 7, 7};
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        server = startServer("/chat/completions",
                "{\"choices\":[{\"message\":{\"content\":\"" + dataUrl + "\"}}]}");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        BlogAgentImageClient client = new BlogAgentImageClient(properties(baseUrl),
                new ObjectMapper(),
                feature -> List.of(new ModelGateway.ModelEndpoint(1L, baseUrl, "test-key",
                        "gpt-image-2", ModelGateway.API_STYLE_CHAT_PROMPT)));

        BlogAgentImageClient.GeneratedImage image = client.generate("draw a robot");

        assertThat(image.bytes()).containsExactly(png);
        assertThat(lastRequestBody).contains("\"prompt\"")
                .contains("\"gpt-image-2\"")
                .doesNotContain("modalities")
                .doesNotContain("\"messages\"");
    }

    @Test
    void chatPromptStyleFallsBackToDataPayload() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 6, 6};
        server = startServer("/chat/completions",
                "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        BlogAgentImageClient client = new BlogAgentImageClient(properties(baseUrl),
                new ObjectMapper(),
                feature -> List.of(new ModelGateway.ModelEndpoint(1L, baseUrl, "test-key",
                        "gpt-image-2", ModelGateway.API_STYLE_CHAT_PROMPT)));

        BlogAgentImageClient.GeneratedImage image = client.generate("draw a robot");

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.bytes()).containsExactly(png);
    }

    private String lastRequestBody;

    private HttpServer startServer(String context, String responseBody) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext(context, exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        httpServer.start();
        return httpServer;
    }

    private BlogAgentProperties properties(String baseUrl) {
        BlogAgentProperties properties = new BlogAgentProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("test-key");
        properties.setImageModel("gpt-image-2");
        properties.setConnectTimeoutMillis(2_000);
        properties.setReadTimeoutMillis(2_000);
        return properties;
    }

    private HttpHeaders headers(Map<String, String> values) {
        return HttpHeaders.of(values.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue()))),
                (name, value) -> true);
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;

        private RepeatingInputStream(long remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            java.util.Arrays.fill(target, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
        }
    }
}
