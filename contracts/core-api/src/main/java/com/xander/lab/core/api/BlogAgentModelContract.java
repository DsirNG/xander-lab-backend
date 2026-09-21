package com.xander.lab.core.api;

/**
 * JDK-only wire shapes for the temporary stateless BlogAgent model boundary.
 * JSON payloads stay strings here so the contract module does not depend on Jackson.
 */
public final class BlogAgentModelContract {

    private BlogAgentModelContract() {
    }

    public record TextRequest(String input) {
    }

    public record ResearchRequest(String input, String analysisJson) {
    }

    public record ReviewRequest(String content, String request) {
    }

    public record RevisionRequest(String content, String instruction) {
    }

    public record ComplianceRequest(String title, String content) {
    }

    public record Response(String payloadJson, long inputTokens, long cachedTokens,
                           long outputTokens, String model) {
    }
}
