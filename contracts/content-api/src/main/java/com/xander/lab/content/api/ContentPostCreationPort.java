package com.xander.lab.content.api;

/**
 * Narrow content creation port used by service boundaries that must preserve publish idempotency.
 * The implementation remains in the publishing domain; callers depend only on this contract.
 */
public interface ContentPostCreationPort {

    /** Creates a draft or published post and reuses a completed request when requestId is present. */
    ContentPost createIdempotent(Long userId, ContentPostInput input, boolean publish, String requestId);
}
