package com.xander.lab.agent.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 这次执行不能被 {@code resume()} 延续。
 *
 * <p>与 {@link AgentRunSupersededException} 的分工：那个表示"**你**已经无权写这一轮了"
 * （代次被顶掉，是一个并发事实，调用方通常静默吞掉）；这个表示"**这一轮本身**不具备被延续的条件"
 * （状态不可恢复、或用户已经开了新意图），是一个需要被解释给调用方的业务结果。</p>
 *
 * <p><b>为什么继承 {@link ResponseStatusException} 而不是裸的 {@code RuntimeException}。</b>
 * 这是"资源当前状态不允许这个动作"，对应 HTTP 409；不继承的话它会被
 * {@code GlobalExceptionHandler} 的兜底分支收成 500，把一次正常的业务拒绝说成服务器故障。
 * 继承之后 {@code getReason()} 就是给调用方的解释，而 {@code GlobalExceptionHandler}
 * 已有的 {@code ResponseStatusException} 分支会把它包进统一的 {@code Result} 信封。</p>
 *
 * <p>{@code reason} 会同时进日志、进自动 resume 的跳过记录、以及（将来）进 HTTP 响应，
 * 所以措辞要能回答"为什么没有自动恢复"，而不是只回答"失败了"，同时不能带上内部标识。</p>
 */
final class AgentRunNotResumableException extends ResponseStatusException {

    private final Long conversationId;
    private final long runVersion;

    AgentRunNotResumableException(Long conversationId, long runVersion, String reason) {
        super(HttpStatus.CONFLICT, reason);
        this.conversationId = conversationId;
        this.runVersion = runVersion;
    }

    Long conversationId() {
        return conversationId;
    }

    long runVersion() {
        return runVersion;
    }
}
