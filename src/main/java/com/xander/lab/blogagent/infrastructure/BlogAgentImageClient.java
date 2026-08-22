package com.xander.lab.blogagent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.xander.lab.admin.application.ModelGateway;
import com.xander.lab.admin.application.ModelGateway.ModelEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Component
public class BlogAgentImageClient {
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Pattern HTTP_STATUS = Pattern.compile("(?i)(?:HTTP\\s*)?([1-5]\\d{2})\\b");
    private static final Pattern SECRET_TOKEN = Pattern.compile(
            "(?i)(?:bearer\\s+|(?:sk|key|token)-)[a-z0-9._~+/=-]{8,}");
    private static final Pattern BASE64_TEXT = Pattern.compile("^[a-zA-Z0-9+/=_-]+$");
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private final BlogAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ModelGateway gateway;
    private final HttpClient downloadClient;
    private final ConcurrentHashMap<String, RestClient> generationClients = new ConcurrentHashMap<>();

    /** 兼容仅依赖环境变量的构造器（测试/独立组件）：关闭管理台网关。 */
    public BlogAgentImageClient(BlogAgentProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, ModelGateway.disabled());
    }

    /** Spring 构造器注入入口。类里有多个构造器，必须显式标注 @Autowired，
     *  否则容器退回找无参构造器导致启动失败。 */
    @Autowired
    public BlogAgentImageClient(BlogAgentProperties properties, ObjectMapper objectMapper, ModelGateway gateway) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.gateway = gateway;
        this.downloadClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(positiveTimeoutMillis(properties.getConnectTimeoutMillis())))
                // Redirect targets must never bypass the address validation below.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 网关启用且已配置时由配置驱动；网关关闭时回退环境变量；未配置视为不可用（不抛出）。 */
    public boolean isEnabled() {
        try {
            if (!gateway.candidates(ModelGateway.FEATURE_BLOG_AGENT_IMAGE).isEmpty()) {
                return true;
            }
        } catch (com.xander.lab.admin.application.FeatureModelNotConfiguredException e) {
            return false;
        }
        return StringUtils.hasText(properties.getApiKey()) && StringUtils.hasText(properties.getImageModel());
    }

    public GeneratedImage generate(String prompt) {
        RuntimeException lastFailure = new IllegalStateException("博客生成助手图片模型尚未配置");
        List<ModelEndpoint> endpoints = endpoints();
        for (int index = 0; index < endpoints.size(); index++) {
            ModelEndpoint endpoint = endpoints.get(index);
            try {
                return generateOnce(endpoint, prompt);
            } catch (RuntimeException e) {
                lastFailure = e;
                if (index == endpoints.size() - 1) {
                    log.error("图片生成模型端点最终失败：provider={} model={} 接口风格={} 原因={}",
                            endpoint.providerId(), endpoint.model(), endpoint.apiStyle(), failureReason(e));
                    throw e;
                }
                // 切换兜底属于已处理的降级，但必须记录失败原因，否则线上只能看到切换看不到根因。
                log.warn("图片生成主模型端点（provider {}，接口风格 {}）调用失败，切换兜底模型，原因：{}",
                        endpoint.providerId(), endpoint.apiStyle(), failureReason(e));
            }
        }
        throw lastFailure;
    }

    /** 提取可日志化的失败原因：只读取供应商标准错误字段，并对内容脱敏、截断。 */
    static String failureReason(RuntimeException e) {
        if (e instanceof RestClientResponseException response) {
            StringBuilder reason = new StringBuilder("HTTP ")
                    .append(response.getStatusCode().value());
            appendProviderError(reason, response.getResponseBodyAsString());
            return reason.toString();
        }
        String message = e.getMessage();
        if (message == null) return e.getClass().getSimpleName();
        var status = HTTP_STATUS.matcher(message);
        if (status.find()) return "HTTP " + status.group(1);
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline);
        }
        if (message.length() > 300) {
            message = message.substring(0, 300) + "...";
        }
        return message;
    }

    private static void appendProviderError(StringBuilder reason, String responseBody) {
        if (!StringUtils.hasText(responseBody)) return;
        try {
            JsonNode root = ERROR_MAPPER.readTree(responseBody);
            JsonNode error = root.path("error");
            if (!error.isObject()) return;
            appendErrorField(reason, "code", error.path("code").asText(null), 80);
            appendErrorField(reason, "type", error.path("type").asText(null), 80);
            appendErrorField(reason, "param", error.path("param").asText(null), 80);
            appendErrorField(reason, "message", error.path("message").asText(null), 240);
        } catch (IOException ignored) {
            // 非 JSON 响应不写入日志，避免代理 HTML 或未知正文泄露。
        }
    }

    private static void appendErrorField(StringBuilder reason, String name, String value, int maxLength) {
        if (!StringUtils.hasText(value)) return;
        String safe = value.replace('\r', ' ').replace('\n', ' ').trim();
        safe = SECRET_TOKEN.matcher(safe).replaceAll("[REDACTED]");
        if (safe.length() > maxLength) safe = safe.substring(0, maxLength) + "...";
        reason.append(' ').append(name).append('=').append(safe);
    }

    /** 解析候选端点：网关启用时由功能模型配置驱动（未配置由网关抛错）；网关关闭时回退环境变量（原行为）。 */
    private List<ModelEndpoint> endpoints() {
        List<ModelEndpoint> candidates = gateway.candidates(ModelGateway.FEATURE_BLOG_AGENT_IMAGE);
        if (!candidates.isEmpty()) {
            return candidates;
        }
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getImageModel())) {
            throw new IllegalStateException("博客生成助手图片模型尚未配置");
        }
        return List.of(new ModelEndpoint(0L, properties.getBaseUrl(),
                properties.getApiKey(), properties.getImageModel(),
                ModelGateway.API_STYLE_CHAT_COMPLETIONS));
    }

    /** 每个端点一个 RestClient，按 baseUrl|apiKey 缓存复用；超时取自 blog.agent 属性。 */
    private RestClient generationClientFor(ModelEndpoint endpoint) {
        return generationClients.computeIfAbsent(endpoint.baseUrl() + "|" + endpoint.apiKey(), key -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(positiveTimeoutMillis(properties.getConnectTimeoutMillis()));
            requestFactory.setReadTimeout(positiveTimeoutMillis(properties.getReadTimeoutMillis()));
            return RestClient.builder()
                    .baseUrl(trimTrailingSlash(endpoint.baseUrl()))
                    .defaultHeader("Authorization", "Bearer " + endpoint.apiKey())
                    .requestFactory(requestFactory)
                    .build();
        });
    }

    private GeneratedImage generateOnce(ModelEndpoint endpoint, String prompt) {
        if (ModelGateway.API_STYLE_CHAT_COMPLETIONS.equals(endpoint.apiStyle())) {
            return generateOnceChatCompletions(endpoint, prompt, true);
        }
        if (ModelGateway.API_STYLE_CHAT_PROMPT.equals(endpoint.apiStyle())) {
            return generateOnceChatCompletions(endpoint, prompt, false);
        }
        return generateOnceImagesGenerations(endpoint, prompt);
    }

    /** OpenAI 传统图片接口：POST /images/generations，响应在 data[0].b64_json / url。 */
    private GeneratedImage generateOnceImagesGenerations(ModelEndpoint endpoint, String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", endpoint.model());
        payload.put("prompt", prompt);
        payload.put("size", properties.getImageSize());
        payload.put("n", 1);

        JsonNode response = generationClientFor(endpoint).post()
                .uri("/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
        logImageModelResponse(endpoint, response);

        JsonNode image = response == null ? objectMapper.createObjectNode() : response.path("data").path(0);
        String base64 = image.path("b64_json").asText();
        if (StringUtils.hasText(base64)) {
            return fromBase64(base64);
        }
        String url = image.path("url").asText();
        if (!StringUtils.hasText(url)) throw new IllegalStateException("图片模型没有返回可读取的图片");
        return download(url);
    }

    /**
     * 聊天补全图片接口：POST /chat/completions（gpt-image 系列）。
     * messages 形态（OpenAI 官方）带 messages + modalities=["image"]；
     * prompt 形态（聚合网关，如 okinto）带顶层 prompt + size + n。
     * 响应图片兼容 choices[0].message.content 的 image_url 片段与 data[0] 的 b64_json/url。
     */
    private GeneratedImage generateOnceChatCompletions(ModelEndpoint endpoint, String prompt, boolean messagesStyle) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", endpoint.model());
        if (messagesStyle) {
            payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            payload.put("modalities", List.of("image"));
        } else {
            payload.put("prompt", prompt);
        }
        payload.put("size", properties.getImageSize());
        payload.put("n", 1);

        JsonNode response = generationClientFor(endpoint).post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
        logImageModelResponse(endpoint, response);

        String url = extractImageUrl(response);
        if (StringUtils.hasText(url)) {
            if (url.startsWith("data:")) {
                return fromDataUrl(url);
            }
            return download(url);
        }
        // 部分网关在 chat 路径上仍返回 images/generations 形态（data[0]）。
        JsonNode image = response == null ? objectMapper.createObjectNode() : response.path("data").path(0);
        String base64 = image.path("b64_json").asText();
        if (StringUtils.hasText(base64)) {
            return fromBase64(base64);
        }
        url = image.path("url").asText();
        if (!StringUtils.hasText(url)) throw new IllegalStateException("图片模型没有返回可读取的图片");
        return download(url);
    }

    /**
     * 临时诊断日志：在解析前保留供应商响应的完整 JSON 结构。
     * 图片二进制、凭证和 URL 查询参数必须脱敏，避免单次响应写入数 MB 日志或泄露签名。
     */
    private void logImageModelResponse(ModelEndpoint endpoint, JsonNode response) {
        try {
            log.info("图片模型原始响应（已脱敏） provider={} model={} apiStyle={} response={}",
                    endpoint.providerId(), endpoint.model(), endpoint.apiStyle(),
                    objectMapper.writeValueAsString(responseForLogging(response)));
        } catch (Exception exception) {
            log.warn("图片模型原始响应序列化失败 provider={} model={} apiStyle={} reason={}",
                    endpoint.providerId(), endpoint.model(), endpoint.apiStyle(),
                    exception.getClass().getSimpleName());
        }
    }

    static JsonNode responseForLogging(JsonNode response) {
        return sanitizeResponseNode(response, "");
    }

    private static JsonNode sanitizeResponseNode(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return NullNode.getInstance();
        }
        if (node.isObject()) {
            ObjectNode sanitized = ERROR_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sanitized.set(field.getKey(), sanitizeResponseNode(field.getValue(), field.getKey()));
            }
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode sanitized = ERROR_MAPPER.createArrayNode();
            node.forEach(value -> sanitized.add(sanitizeResponseNode(value, fieldName)));
            return sanitized;
        }
        if (!node.isTextual()) {
            return node.deepCopy();
        }
        return TextNode.valueOf(sanitizeResponseText(fieldName, node.asText()));
    }

    private static String sanitizeResponseText(String fieldName, String value) {
        String normalizedField = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (normalizedField.equals("b64_json")
                || normalizedField.equals("base64")
                || normalizedField.equals("image_base64")
                || normalizedField.endsWith("_b64")) {
            return "[REDACTED image bytes length=" + value.length() + "]";
        }
        if (normalizedField.contains("authorization")
                || normalizedField.equals("api_key")
                || normalizedField.equals("apikey")
                || normalizedField.endsWith("token")) {
            return "[REDACTED]";
        }
        if (value.regionMatches(true, 0, "data:image/", 0, "data:image/".length())) {
            int comma = value.indexOf(',');
            if (comma >= 0) {
                return value.substring(0, comma + 1)
                        + "[REDACTED image bytes length=" + (value.length() - comma - 1) + "]";
            }
        }
        if (value.length() > 8192 && BASE64_TEXT.matcher(value).matches()) {
            return "[REDACTED probable binary length=" + value.length() + "]";
        }
        String safe = SECRET_TOKEN.matcher(value).replaceAll("[REDACTED]");
        if (safe.regionMatches(true, 0, "https://", 0, "https://".length())
                || safe.regionMatches(true, 0, "http://", 0, "http://".length())) {
            int query = safe.indexOf('?');
            if (query >= 0) safe = safe.substring(0, query) + "?[REDACTED]";
            int fragment = safe.indexOf('#');
            if (fragment >= 0) safe = safe.substring(0, fragment) + "#[REDACTED]";
        }
        return safe;
    }

    /** 从 chat/completions 响应中取第一个图片地址（content 兼容字符串或片段数组）。 */
    private String extractImageUrl(JsonNode response) {
        if (response == null) return null;
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            String text = content.asText();
            return (text.startsWith("data:") || text.startsWith("http")) ? text : null;
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                String url = part.path("image_url").path("url").asText();
                if (!StringUtils.hasText(url)) {
                    url = part.path("url").asText();
                }
                if (StringUtils.hasText(url)) return url;
            }
        }
        return null;
    }

    private GeneratedImage fromBase64(String base64) {
        checkEncodedSize(base64);
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("图片模型返回的图片超过 10MB 限制");
        }
        return new GeneratedImage(bytes, "image/png", "png");
    }

    /** data:image/png;base64,.... 形式：校验 mime 并解码，避免任意 data 协议进入下载器。 */
    private GeneratedImage fromDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma <= 0) throw new IllegalStateException("图片模型返回的图片地址无效");
        String meta = dataUrl.substring(5, comma);
        int semicolon = meta.indexOf(';');
        String mime = (semicolon > 0 ? meta.substring(0, semicolon) : meta).trim().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/")) {
            throw new IllegalStateException("图片模型返回的图片地址不是图片类型");
        }
        String base64 = dataUrl.substring(comma + 1);
        checkEncodedSize(base64);
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("图片模型返回的图片超过 10MB 限制");
        }
        String extension = mime.contains("jpeg") ? "jpg"
                : mime.contains("webp") ? "webp"
                : mime.contains("gif") ? "gif" : "png";
        return new GeneratedImage(bytes, mime, extension);
    }

    private void checkEncodedSize(String base64) {
        long maximumEncodedLength = ((long) MAX_IMAGE_BYTES + 2L) / 3L * 4L;
        if (base64.length() > maximumEncodedLength) {
            throw new IllegalStateException("图片模型返回的图片超过 10MB 限制");
        }
    }

    private GeneratedImage download(String url) {
        URI uri = validateDownloadUri(url, InetAddress::getAllByName);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(positiveTimeoutMillis(properties.getReadTimeoutMillis())))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = downloadClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            return readDownloadedImage(response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载生成图片被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("下载生成图片连接失败", e);
        }
    }

    static URI validateDownloadUri(String rawUrl, HostResolver resolver) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("图片模型返回的下载地址无效", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !StringUtils.hasText(uri.getHost())
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException("图片模型返回的下载地址必须是无凭据的 HTTPS 地址");
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("图片模型返回的下载地址无法解析", exception);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalStateException("图片模型返回的下载地址无法解析");
        }
        for (InetAddress address : addresses) {
            if (address == null || isBlockedAddress(address)) {
                throw new IllegalStateException("图片模型返回的下载地址指向非公网地址");
            }
        }
        return uri;
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            // Java's site-local check does not include IPv6 unique-local fc00::/7.
            return (bytes[0] & 0xfe) == 0xfc;
        }
        if (bytes.length != 4) return true;
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        // Reject IPv4 special-use ranges commonly routed inside hosting networks.
        return first == 0
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 198 && (second == 18 || second == 19))
                || first >= 224;
    }

    static GeneratedImage readDownloadedImage(int statusCode, HttpHeaders headers, InputStream body) {
        try (InputStream input = body) {
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("下载生成图片失败（HTTP " + statusCode + "）");
            }
            String contentType = headers.firstValue("Content-Type")
                    .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                    .orElse("");
            if (!contentType.startsWith("image/")) {
                throw new IllegalStateException("生成图片下载响应不是图片类型");
            }
            long declaredLength = headers.firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > MAX_IMAGE_BYTES) {
                throw new IllegalStateException("生成图片下载响应超过 10MB 限制");
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    declaredLength > 0 ? (int) declaredLength : 8192);
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total > MAX_IMAGE_BYTES - read) {
                    throw new IllegalStateException("生成图片下载响应超过 10MB 限制");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            if (total == 0) throw new IllegalStateException("生成图片下载响应为空");
            String extension = contentType.contains("jpeg") ? "jpg"
                    : contentType.contains("webp") ? "webp"
                    : contentType.contains("gif") ? "gif" : "png";
            return new GeneratedImage(output.toByteArray(), contentType, extension);
        } catch (IOException exception) {
            throw new IllegalStateException("读取生成图片下载响应失败", exception);
        }
    }

    static int positiveTimeoutMillis(int configured) {
        return Math.max(1, configured);
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record GeneratedImage(byte[] bytes, String contentType, String extension) {}
}
