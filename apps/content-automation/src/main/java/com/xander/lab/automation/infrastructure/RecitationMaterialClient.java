package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/** Validates optional knowledge-material ownership through the core API boundary. */
@Component
@RequiredArgsConstructor
public class RecitationMaterialClient {

    private final RestClient.Builder restClientBuilder;
    private final ContentAutomationProperties properties;

    public void requireOwnedMaterial(Long materialId, String authorization) {
        if (materialId == null) {
            return;
        }
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "绑定知识材料需要登录凭证");
        }
        String baseUrl = properties.getCoreBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("content-automation.core-base-url 不能为空");
        }
        restClientBuilder.build()
                .get()
                .uri(URI.create(baseUrl.replaceAll("/+$", "")
                        + "/api/recitations/materials/" + materialId))
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (!status.is2xxSuccessful()) {
                        throw new ResponseStatusException(status,
                                "知识材料不存在或不属于当前用户");
                    }
                    return null;
                });
    }
}
