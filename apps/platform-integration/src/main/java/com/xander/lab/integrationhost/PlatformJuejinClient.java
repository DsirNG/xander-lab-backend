package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Minimal Juejin vendor port needed by the Platform-owned catalog workflow. */
public interface PlatformJuejinClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Credential(String cookie, String csrfToken, String aid, String uuid,
                      String juejinUserId, String secUserId, String username,
                      String avatarUrl, String mobile, String sessionKey) {
        public Credential(String cookie, String csrfToken, String aid, String uuid) {
            this(cookie, csrfToken, aid, uuid, null, null, null, null, null, null);
        }
    }

    JsonNode queryCategories(Credential credential);

    JsonNode queryTagList(Credential credential, String cursor, int limit);

    /** Fetches the logged-in Juejin profile used only to enrich the stored credential display name. */
    JsonNode fetchUserProfile(Credential credential);
}
