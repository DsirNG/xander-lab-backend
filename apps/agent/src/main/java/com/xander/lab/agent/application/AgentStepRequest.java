package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentInputTurn;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Immutable model-call input assembled for one durable agent step.
 *
 * <p>{@code turns} and {@code toolDescriptions} are transient renderings for the model.
 * {@code journalSnapshotJson} contains only durable references and safe assembly metadata,
 * never a second copy of the user prompt.</p>
 *
 * <p>{@code turns} 是 role 化的输入序列（见 {@link AgentInputTurn}），已经合并过相邻同 role，
 * 可以直接发给模型。对话记录在前、一轮之内会变的会话状态收在最后一轮，
 * 这样同一轮里前面那一大段的字节是稳定的，前缀缓存才能命中。</p>
 *
 * <p>{@code toolDescriptions} 是完整的工具面：schema 表加上在表工具自带的使用准则
 * （见 {@link AgentToolRegistry#describeToolSurface}）。准则算在里面是有意的——
 * 换了准则就是换了工具契约，{@code toolSchemaSetHash} 应该跟着变。</p>
 */
public record AgentStepRequest(
        Long conversationId,
        Long userId,
        long runVersion,
        int stepNo,
        List<AgentInputTurn> turns,
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
        turns = List.copyOf(turns);
        attachments = List.copyOf(attachments);
        contextMessageIds = List.copyOf(contextMessageIds);
        toolNames = List.copyOf(toolNames);
    }

    /** 全部轮次拼成的纯文本，仅用于日志、度量和断言；发给模型的始终是 {@link #turns()}。 */
    public String context() {
        return turns.stream().map(AgentInputTurn::text).collect(Collectors.joining("\n\n"));
    }
}
