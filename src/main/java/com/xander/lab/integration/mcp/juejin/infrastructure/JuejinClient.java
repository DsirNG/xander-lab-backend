package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Bounded adapter over juejin.cn content_api. Translation of vendor errors happens here:
 * every call returns the raw {@code err_no}/{@code data} contract; auth failures surface
 * {@link JuejinSessionException} so the credential layer can mark the credential expired.
 */
public interface JuejinClient {

    /**
     * Normalized per-user request context captured during authorization. The trailing user-profile
     * fields are informational only (never sent to juejin.cn); they let the credential layer persist
     * the account identity harvested at login time.
     */
    record Credential(String cookie, String csrfToken, String aid, String uuid,
                      String juejinUserId, String secUserId, String username,
                      String avatarUrl, String mobile, String sessionKey) {

        public Credential(String cookie, String csrfToken, String aid, String uuid) {
            this(cookie, csrfToken, aid, uuid, null, null, null, null, null, null);
        }
    }

    /** Vendor-level failure carrying the API err_no; thrown on err_no != 0 or HTTP errors. */
    class JuejinApiException extends RuntimeException {
        private final int errNo;

        public JuejinApiException(int errNo, String errMsg) {
            super(errMsg);
            this.errNo = errNo;
        }

        public int errNo() { return errNo; }
    }

    /** Raised when the Juejin session is invalid (HTTP 401/403 or body err_no 401); callers mark credentials EXPIRED. */
    class JuejinSessionException extends RuntimeException {
        public JuejinSessionException(String message) { super(message); }
    }

    /**
     * POST /content_api/v1/article_draft/create. Returns the response root node; caller reads
     * {@code data.id} as the draft id.
     */
    JsonNode createDraft(Credential credential, String title, String briefContent, String markContent,
                         String categoryId, java.util.List<String> tagIds, String coverImage);

    /**
     * POST /content_api/v1/article_draft/update. id must be an existing draft id.
     */
    JsonNode updateDraft(Credential credential, String draftId, String title, String briefContent, String markContent,
                         String categoryId, java.util.List<String> tagIds, String coverImage);

    /**
     * POST /content_api/v1/article/publish. Publishes the draft with the given id;
     * returns the response root node whose data carries the article id.
     */
    JsonNode publishDraft(Credential credential, String draftId);

    /**
     * GET /user_api/v1/user/get?not_self=0. Fetches the logged-in account's public profile
     * (user_id, user_name, avatar_large, level, growth info, ...). Returns the response root node.
     */
    JsonNode fetchUserProfile(Credential credential);
}