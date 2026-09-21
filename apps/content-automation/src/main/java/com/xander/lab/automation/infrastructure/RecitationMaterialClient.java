package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.recitation.spi.MaterialView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/** Validates optional knowledge-material ownership through the core API boundary. */
@Component
@RequiredArgsConstructor
public class RecitationMaterialClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

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

    /**
     * Reads an owned material for a background run through the internal contract boundary.
     *
     * <p>The scheduled executor has no user JWT to forward. The explicit user id is therefore
     * part of the request body and the core recitation port performs the ownership predicate.
     * This keeps content-automation from importing recitation persistence or treating a material
     * id as globally readable.</p>
     */
    public MaterialView getMaterial(Long userId, Long materialId) {
        if (userId == null || userId <= 0 || materialId == null || materialId <= 0) {
            throw new IllegalArgumentException("知识材料引用不完整");
        }
        String baseUrl = properties.getCoreBaseUrl();
        String internalToken = properties.getCoreInternalToken();
        if (baseUrl == null || baseUrl.isBlank() || internalToken == null || internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "知识材料服务尚未配置内网凭证");
        }
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            int timeout = Math.max(1000, properties.getModelTimeoutMillis());
            requestFactory.setConnectTimeout(timeout);
            requestFactory.setReadTimeout(timeout);
            MaterialView material = restClientBuilder.requestFactory(requestFactory).build()
                    .post()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "")
                            + "/internal/agent/recitation/material/get"))
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .body(new MaterialRequest(userId, materialId))
                    .retrieve()
                    .body(MaterialView.class);
            if (material == null) {
                throw new IllegalStateException("知识材料服务没有返回材料");
            }
            return material;
        } catch (RestClientException exception) {
            if (exception instanceof org.springframework.web.client.HttpClientErrorException.NotFound) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "知识材料不存在或不属于当前用户");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "知识材料服务暂时无法连接", exception);
        }
    }

    private record MaterialRequest(Long userId, Long materialId) {
    }
}
