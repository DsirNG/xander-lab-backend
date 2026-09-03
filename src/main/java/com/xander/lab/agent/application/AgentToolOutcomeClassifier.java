package com.xander.lab.agent.application;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/** Classifies tool failures whose remote side effect may already have started. */
final class AgentToolOutcomeClassifier {
    private AgentToolOutcomeClassifier() {
    }

    static boolean requiresReconciliation(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof ResourceAccessException) {
                return true;
            }
            if (current instanceof RestClientResponseException response
                    && response.getStatusCode().is5xxServerError()) {
                return true;
            }
        }
        return false;
    }
}
