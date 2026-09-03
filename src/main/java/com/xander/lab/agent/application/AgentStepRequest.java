package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentAttachment;

import java.util.List;

/**
 * Immutable model-call input assembled for one durable agent step.
 *
 * <p>{@code context} and {@code toolDescriptions} are transient renderings for the model.
 * {@code journalSnapshotJson} contains only durable references and safe assembly metadata,
 * never a second copy of the user prompt.</p>
 */
public record AgentStepRequest(
        Long conversationId,
        Long userId,
        long runVersion,
        int stepNo,
        String context,
        String toolDescriptions,
        List<AgentAttachment> attachments,
        List<Long> contextMessageIds,
        List<String> toolNames,
        String toolSchemaSetHash,
        String modelRoute,
        String instructionSetVersion,
        int remainingSteps,
        boolean deepThinking,
        String journalSnapshotJson) {

    public AgentStepRequest {
        attachments = List.copyOf(attachments);
        contextMessageIds = List.copyOf(contextMessageIds);
        toolNames = List.copyOf(toolNames);
    }
}
