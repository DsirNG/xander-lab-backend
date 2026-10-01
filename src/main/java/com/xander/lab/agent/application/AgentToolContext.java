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
 * @param effectId       当前调用所属**逻辑副作用**的稳定身份；只读工具与迁移前的 Run 为 null。
 *                       见 {@link #idempotencyKey()}
 */
public record AgentToolContext(Long userId, Long conversationId, long runVersion, String invocationId,
                               String effectId) {

    /** Compatibility constructor for tools that predate effect identity. */
    public AgentToolContext(Long userId, Long conversationId, long runVersion, String invocationId) {
        this(userId, conversationId, runVersion, invocationId, null);
    }

    /** Compatibility constructor for tools and tests that do not need invocation identity yet. */
    public AgentToolContext(Long userId, Long conversationId, long runVersion) {
        this(userId, conversationId, runVersion, null, null);
    }

    public static AgentToolContext ofUser(Long userId) {
        return new AgentToolContext(userId, null, 0L, null, null);
    }

    public boolean hasConversation() {
        return conversationId != null;
    }

    /**
     * 交给下游做幂等去重的键（写工具把它填进 {@code client_request_id}）。
     *
     * <p><b>优先用 {@code effectId}</b>：它跨多次 invocation 保持不变，所以"模型重新发起同一个调用"
     * 会带同一个键，下游唯一键命中，不会真的执行第二次。这正是 B6 修的东西——
     * 以前这里用的是 {@code invocationId}，每次调用都是新 UUID，重试因此总是穿过去。</p>
     *
     * <p><b>为什么还要回退到 {@code invocationId}。</b>两种情况下没有 {@code effectId}：
     * 只读工具（本来就不需要），以及**迁移之前创建的 Run**（没有 {@code intent_epoch}，
     * 无法界定"同一件事"的范围，见 {@code AgentEffectService#resolveEffect}）。回退到
     * {@code invocationId} 让这两种情况保持 B6 之前的行为——幂等键照样有值，
     * 只是粒度是"每次调用"。不回退会让这些写入**丢掉幂等键**，那是比不升级更差的回归。</p>
     *
     * <p>这个解析只在这里出现一次。三个写工具各写一遍"effectId 为空时用什么"，
     * 迟早会有一处忘了回退。</p>
     */
    public String idempotencyKey() {
        if (effectId != null && !effectId.isBlank()) return effectId;
        return invocationId;
    }

    /** 是否已经拿到了逻辑副作用身份（只读工具与迁移前的 Run 为 false）。 */
    public boolean hasEffect() {
        return effectId != null && !effectId.isBlank();
    }
}
