package com.xander.lab.recitation.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.admin.application.ModelGateway;
import com.xander.lab.admin.application.ModelGateway.ModelEndpoint;
import com.xander.lab.recitation.application.RecitationAsrPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 使用管理台模型配置调用 DashScope Paraformer 异步录音转写。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeRecitationAsrClient implements RecitationAsrPort {
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public SubmittedTask submit(String audioUrl) {
        RuntimeException lastError = null;
        for (ModelEndpoint endpoint : modelGateway.candidates(ModelGateway.FEATURE_RECITATION_ASR)) {
            if (!ModelGateway.API_STYLE_DASHSCOPE_ASR.equals(endpoint.apiStyle())) continue;
            try {
                return submit(endpoint, audioUrl);
            } catch (RuntimeException error) {
                lastError = error;
                log.warn("recitation ASR submit failed for provider {}", endpoint.providerId());
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("背诵音转文模型未正确配置");
    }

    @Override
    public PollResult poll(long providerId, String taskId) {
        ModelEndpoint endpoint = modelGateway.candidates(ModelGateway.FEATURE_RECITATION_ASR).stream()
                .filter(candidate -> candidate.providerId() == providerId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("原音转文供应商已停用，无法继续查询任务"));
        try {
            String safeTaskId = URLEncoder.encode(taskId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(apiUri(endpoint, "/tasks/" + safeTaskId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            JsonNode body = sendJson(request);
            String status = body.path("output").path("task_status").asText("");
            if ("PENDING".equals(status) || "RUNNING".equals(status)) {
                return new PollResult(PollResult.State.PROCESSING, null, List.of(), null);
            }
            if (!"SUCCEEDED".equals(status)) {
                return new PollResult(PollResult.State.FAILED, null, List.of(), "语音识别任务执行失败");
            }
            JsonNode successful = firstSuccessfulResult(body.path("output").path("results"));
            if (successful == null || !StringUtils.hasText(successful.path("transcription_url").asText())) {
                return new PollResult(PollResult.State.FAILED, null, List.of(), "语音识别结果不可用");
            }
            return downloadTranscript(successful.path("transcription_url").asText());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("查询语音识别任务被中断", interrupted);
        } catch (Exception error) {
            throw new IllegalStateException("查询语音识别任务失败", error);
        }
    }

    private SubmittedTask submit(ModelEndpoint endpoint, String audioUrl) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", endpoint.model());
            ObjectNode input = objectMapper.createObjectNode();
            input.set("file_urls", objectMapper.createArrayNode().add(audioUrl));
            payload.set("input", input);
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.set("channel_id", objectMapper.createArrayNode().add(0));
            parameters.set("language_hints", objectMapper.createArrayNode().add("zh"));
            payload.set("parameters", parameters);
            HttpRequest request = HttpRequest.newBuilder(apiUri(endpoint, "/services/audio/asr/transcription"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            JsonNode body = sendJson(request);
            String taskId = body.path("output").path("task_id").asText("");
            if (!StringUtils.hasText(taskId)) throw new IllegalStateException("音转文服务未返回任务 ID");
            return new SubmittedTask(endpoint.providerId(), endpoint.model(), taskId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("提交语音识别任务被中断", interrupted);
        } catch (Exception error) {
            throw error instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("提交语音识别任务失败", error);
        }
    }

    private JsonNode firstSuccessfulResult(JsonNode results) {
        if (!results.isArray()) return null;
        for (JsonNode result : results) {
            if ("SUCCEEDED".equals(result.path("subtask_status").asText())) return result;
        }
        return null;
    }

    private PollResult downloadTranscript(String url) throws Exception {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !host.endsWith(".aliyuncs.com")) {
            throw new IllegalStateException("语音识别结果地址不受信任");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("无法下载语音识别结果");
        }
        byte[] bytes;
        try (InputStream input = response.body()) {
            bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("语音识别结果过大");
        JsonNode root = objectMapper.readTree(bytes);
        StringBuilder transcript = new StringBuilder();
        List<WordTimestamp> words = new ArrayList<>();
        for (JsonNode channel : root.path("transcripts")) {
            transcript.append(channel.path("text").asText(""));
            for (JsonNode sentence : channel.path("sentences")) {
                for (JsonNode word : sentence.path("words")) {
                    words.add(new WordTimestamp(word.path("text").asText(""),
                            word.path("begin_time").asInt(), word.path("end_time").asInt()));
                }
            }
        }
        if (transcript.isEmpty()) {
            return new PollResult(PollResult.State.FAILED, null, List.of(), "录音中未识别到可用中文内容");
        }
        return new PollResult(PollResult.State.SUCCEEDED, transcript.toString(), words, null);
    }

    private JsonNode sendJson(HttpRequest request) throws Exception {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("音转文服务请求失败（HTTP " + response.statusCode() + "）");
        }
        if (response.body().length > MAX_RESPONSE_BYTES) throw new IllegalStateException("音转文响应过大");
        return objectMapper.readTree(response.body());
    }

    private URI apiUri(ModelEndpoint endpoint, String path) {
        URI base = URI.create(endpoint.baseUrl());
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null) {
            throw new IllegalStateException("音转文供应商必须使用 HTTPS 地址");
        }
        return URI.create(endpoint.baseUrl() + path);
    }
}
