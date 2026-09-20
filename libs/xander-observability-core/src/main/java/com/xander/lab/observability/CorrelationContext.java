package com.xander.lab.observability;

import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, sanitized correlation identifiers for one request boundary. */
public record CorrelationContext(String traceId, String requestId) {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /**
     * Creates a context from inbound headers, replacing missing or unsafe values.
     * Header values are untrusted input and must be safe before they reach logs or responses.
     */
    public static CorrelationContext fromHeaders(String traceIdHeader, String requestIdHeader) {
        return new CorrelationContext(normalizeHeader(traceIdHeader), normalizeHeader(requestIdHeader));
    }

    /** Creates a new opaque identifier for an outbound correlation context. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public CorrelationContext {
        traceId = requireSafe("traceId", traceId);
        requestId = requireSafe("requestId", requestId);
    }

    private static String normalizeHeader(String value) {
        if (value == null) {
            return newId();
        }
        String candidate = value.trim();
        return SAFE_ID.matcher(candidate).matches() ? candidate : newId();
    }

    private static String requireSafe(String name, String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must contain only safe correlation characters");
        }
        return value;
    }
}
