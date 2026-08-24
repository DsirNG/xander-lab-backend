package com.xander.lab.agent.domain;

/** Metadata for one user-uploaded image or document attached to an agent turn. */
public record AgentAttachment(
        String url,
        String name,
        String contentType,
        long size) {

    /** Images are sent as Responses API vision inputs; other supported types are sent as file inputs. */
    public boolean image() {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/");
    }
}
