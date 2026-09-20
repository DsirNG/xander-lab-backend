package com.xander.lab.observability;

/** Shared header and MDC names used when a request crosses service boundaries. */
public final class CorrelationHeaders {

    /** Header carrying the trace identifier shared by one distributed operation. */
    public static final String TRACE_ID = "X-Trace-Id";

    /** Header carrying the identifier of one inbound request. */
    public static final String REQUEST_ID = "X-Request-Id";

    /** MDC key for the trace identifier in thread-bound adapters. */
    public static final String MDC_TRACE_ID = "traceId";

    /** MDC key for the request identifier in thread-bound adapters. */
    public static final String MDC_REQUEST_ID = "requestId";

    private CorrelationHeaders() {
    }
}
