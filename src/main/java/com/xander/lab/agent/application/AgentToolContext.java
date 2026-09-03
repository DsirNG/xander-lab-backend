package com.xander.lab.agent.application;

/**
 * 一次工具调用所处的会话执行上下文。
 *
 * <p>大多数工具只需要 userId，因此 {@link AgentTool} 保留了只带 userId 的重载；
 * 只有需要写回会话状态的工具（例如任务计划）才需要知道自己属于哪个会话的哪一代执行，
 * 以便在被新一轮取代后拒绝迟到写入。</p>
 *
 * @param userId         归属用户
 * @param conversationId 所属会话；无会话场景（如单元测试直接调用工具）可为 null
 * @param runVersion     当前执行代次
 * @param invocationId   当前工具调用的稳定标识；无持久化上下文时为 null
 */
public record AgentToolContext(Long userId, Long conversationId, long runVersion, String invocationId) {

    /** Compatibility constructor for tools and tests that do not need invocation identity yet. */
    public AgentToolContext(Long userId, Long conversationId, long runVersion) {
        this(userId, conversationId, runVersion, null);
    }

    public static AgentToolContext ofUser(Long userId) {
        return new AgentToolContext(userId, null, 0L, null);
    }

    public boolean hasConversation() {
        return conversationId != null;
    }
}
