package com.xander.lab.identity.api;

import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.content.api.StoredImage;
import com.xander.lab.identity.application.SecurityStateSyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Agent 专用的内容媒体契约 API；默认关闭，不暴露媒体库管理接口。 */
@RestController
@RequestMapping("/internal/agent/media")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class AgentMediaAssetController {
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final MediaAssetPort mediaAssetPort;
    private final SecurityStateSyncProperties properties;

    public AgentMediaAssetController(MediaAssetPort mediaAssetPort,
                                     SecurityStateSyncProperties properties) {
        this.mediaAssetPort = mediaAssetPort;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网媒体 API 时不能为空");
        }
    }

    @PostMapping("/record-stored")
    public MediaAssetRef recordStored(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                      @RequestBody RecordStoredRequest request) {
        authorize(token);
        return mediaAssetPort.recordStored(request.userId(), request.stored());
    }

    @PostMapping("/record-generated")
    public MediaAssetRef recordGenerated(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody RecordGeneratedRequest request) {
        authorize(token);
        return mediaAssetPort.recordGenerated(request.userId(), request.taskId(), request.originalName(),
                request.content(), request.contentType(), request.generationMeta());
    }

    @PostMapping("/upload-image")
    public MediaAssetRef uploadImage(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                     @RequestBody UploadImageRequest request) {
        authorize(token);
        return mediaAssetPort.uploadImage(request.userId(), request.content(), request.originalName(),
                request.contentType());
    }

    @PostMapping("/find-owned")
    public MediaAssetRef findOwned(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                   @RequestBody AssetRequest request) {
        authorize(token);
        return mediaAssetPort.findOwned(request.userId(), request.assetId());
    }

    @PostMapping("/list-by-task")
    public MediaAssetList listByTask(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                     @RequestBody TaskRequest request) {
        authorize(token);
        return new MediaAssetList(mediaAssetPort.listByTask(request.userId(), request.taskId()));
    }

    @PostMapping("/find-reusable-generated")
    public MediaAssetRef findReusableGenerated(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody ReusableRequest request) {
        authorize(token);
        return mediaAssetPort.findReusableGenerated(request.userId(), request.title());
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record RecordStoredRequest(Long userId, StoredImage stored) {
    }

    public record RecordGeneratedRequest(Long userId, Long taskId, String originalName,
                                        byte[] content, String contentType, String generationMeta) {
    }

    public record UploadImageRequest(Long userId, byte[] content, String originalName, String contentType) {
    }

    public record AssetRequest(Long userId, Long assetId) {
    }

    public record TaskRequest(Long userId, Long taskId) {
    }

    public record ReusableRequest(Long userId, String title) {
    }

    public record MediaAssetList(List<MediaAssetRef> assets) {
    }
}
