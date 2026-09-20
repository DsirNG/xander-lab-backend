package com.xander.lab.agent.infrastructure;

import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.content.api.StoredImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/** 通过 core-host 内网契约 API 调用内容媒体服务。 */
@Component
@ConditionalOnProperty(prefix = "agent.core-client", name = "enabled", havingValue = "true")
public class AgentMediaAssetHttpAdapter implements MediaAssetPort {
    private static final Logger log = LoggerFactory.getLogger(AgentMediaAssetHttpAdapter.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final String internalToken;

    @Autowired
    public AgentMediaAssetHttpAdapter(RestClient.Builder builder,
                                      AgentCoreClientProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("agent.core-client.base-url 在启用 core-client 时不能为空");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "agent.core-client.internal-token 在启用 core-client 时不能为空");
        }
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        this.restClient = builder.baseUrl(baseUrl).build();
        this.internalToken = properties.getInternalToken();
    }

    @Override
    public MediaAssetRef recordStored(Long userId, StoredImage stored) {
        return post("/internal/agent/media/record-stored", new RecordStoredRequest(userId, stored),
                MediaAssetRef.class);
    }

    @Override
    public MediaAssetRef recordGenerated(Long userId, Long taskId, String originalName,
                                         byte[] content, String contentType, String generationMeta) {
        return post("/internal/agent/media/record-generated",
                new RecordGeneratedRequest(userId, taskId, originalName, content, contentType, generationMeta),
                MediaAssetRef.class);
    }

    @Override
    public MediaAssetRef uploadImage(Long userId, byte[] content, String originalName, String contentType) {
        return post("/internal/agent/media/upload-image",
                new UploadImageRequest(userId, content, originalName, contentType), MediaAssetRef.class);
    }

    @Override
    public MediaAssetRef findOwned(Long userId, Long assetId) {
        return post("/internal/agent/media/find-owned", new AssetRequest(userId, assetId),
                MediaAssetRef.class);
    }

    @Override
    public List<MediaAssetRef> listByTask(Long userId, Long taskId) {
        MediaAssetList response = post("/internal/agent/media/list-by-task",
                new TaskRequest(userId, taskId), MediaAssetList.class);
        return response == null || response.assets() == null ? List.of() : response.assets();
    }

    @Override
    public MediaAssetRef findReusableGenerated(Long userId, String title) {
        return post("/internal/agent/media/find-reusable-generated",
                new ReusableRequest(userId, title), MediaAssetRef.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            return restClient.post()
                    .uri(path)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientException exception) {
            log.warn("core-host media boundary unavailable; rejecting media operation");
            throw new IllegalStateException("core-host media boundary unavailable", exception);
        }
    }

    private record RecordStoredRequest(Long userId, StoredImage stored) {
    }

    private record RecordGeneratedRequest(Long userId, Long taskId, String originalName,
                                         byte[] content, String contentType, String generationMeta) {
    }

    private record UploadImageRequest(Long userId, byte[] content, String originalName, String contentType) {
    }

    private record AssetRequest(Long userId, Long assetId) {
    }

    private record TaskRequest(Long userId, Long taskId) {
    }

    private record ReusableRequest(Long userId, String title) {
    }

    private record MediaAssetList(List<MediaAssetRef> assets) {
    }
}
