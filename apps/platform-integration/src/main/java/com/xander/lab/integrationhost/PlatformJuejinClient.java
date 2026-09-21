package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Minimal Juejin vendor port needed by the Platform-owned catalog workflow. */
public interface PlatformJuejinClient {

    /** Vendor error that is safe to expose to the Platform application layer. */
    class JuejinApiException extends RuntimeException {
        private final int errNo;

        public JuejinApiException(int errNo, String message) {
            super(message);
            this.errNo = errNo;
        }

        public JuejinApiException(String message) {
            this(-1, message);
        }

        public int errNo() {
            return errNo;
        }
    }

    /** Authentication failure; callers must mark the stored browser session expired. */
    class JuejinSessionException extends JuejinApiException {
        public JuejinSessionException(String message) {
            super(401, message);
        }

        public JuejinSessionException(String message, Throwable cause) {
            super(401, message);
            initCause(cause);
        }
    }

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

    /** Creates a Juejin draft from the explicit content payload supplied by content-automation. */
    JsonNode createDraft(Credential credential, String title, String briefContent, String markContent,
                         String categoryId, List<String> tagIds, String coverImage);

    /** Publishes a previously created Juejin draft. */
    JsonNode publishDraft(Credential credential, String draftId);

    /** Searches the public Juejin tag catalogue when the local projection has no exact match. */
    JsonNode searchTags(String keyword, int limit);
}
