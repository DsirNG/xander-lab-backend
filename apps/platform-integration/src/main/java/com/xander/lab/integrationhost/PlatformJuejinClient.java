package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Minimal Juejin vendor port needed by the Platform-owned catalog workflow. */
public interface PlatformJuejinClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Credential(String cookie, String csrfToken, String aid, String uuid) {
    }

    JsonNode queryCategories(Credential credential);

    JsonNode queryTagList(Credential credential, String cursor, int limit);
}
