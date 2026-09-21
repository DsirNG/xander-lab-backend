package com.xander.lab.publishing.api;

import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.publishing.application.ContentExternalPublicationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Agent → Content boundary that keeps external publication records in Content. */
@RestController
@RequestMapping("/internal/agent/publications")
public class ContentExternalPublicationController {

    private static final String TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final ContentExternalPublicationService service;
    private final ContentAutomationProperties properties;

    public ContentExternalPublicationController(ContentExternalPublicationService service,
                                                ContentAutomationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/{postId}/sync/{platform}")
    public BlogSyncResult sync(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                               @PathVariable Long postId,
                               @PathVariable String platform,
                               @RequestBody SyncRequest request) {
        authorize(token);
        if (request == null || request.userId() == null || !postId.equals(request.postId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "站外同步请求参数非法");
        }
        return service.sync(request.userId(), postId, platform);
    }

    @PostMapping("/{postId}/find-succeeded/{platform}")
    public BlogSyncResult findSucceeded(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable Long postId,
            @PathVariable String platform,
            @RequestBody SyncRequest request) {
        authorize(token);
        if (request == null || request.userId() == null || !postId.equals(request.postId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "站外同步请求参数非法");
        }
        return service.findSucceeded(request.userId(), postId, platform);
    }

    private void authorize(String token) {
        String expected = properties.getAgentInternalToken();
        if (expected == null || expected.isBlank() || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record SyncRequest(Long userId, Long postId) {
    }
}
