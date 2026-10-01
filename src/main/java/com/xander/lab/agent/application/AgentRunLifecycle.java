package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xander.lab.agent.domain.AgentApproval;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentRunStatusProjection;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns fenced durable terminal transitions for an agent conversation run.
 *
 * <p><b>本类是唯一允许写 {@code agent_conversation.status} 的地方</b>，而且每个写入值都必须由
 * {@link AgentRunStatusProjection#of} 从对应的 {@link AgentRun} 状态算出来。会话状态是权威状态
 * 的投影，不是可以就地拼的字符串——以前 {@code claim()} 手写 {@code "ready"}、
 * {@code fail()} 手写 {@code "failed"}、{@code recoverStale()} 再写一次 {@code "failed"}，
 * 同一件事三处拼写，任何一次复制粘贴写错都只在某个前端分支上看得出来。</p>
 *
 * <p>每条迁移都带 {@code run_version} 守卫：代次对不上说明这一轮已被新一轮顶掉，
 * 迟到写入必须失败而不是覆盖新状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunLifecycle {
    private static final long MAX_LEASE_RENEW_INTERVAL_SECONDS = 60L;
    private static final String BUSY_MESSAGE = "智能体正在执行中，请稍候";
    private static final String BLOCKED_MESSAGE = "当前会话状态不能继续对话";

    private final AgentConversationMapper conversationMapper;
    private final AgentEventService eventService;
    private final AgentExecutionJournal executionJournal;
    private final AgentConversationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ScheduledExecutorService agentLeaseRenewer;

    /**
     * Claims an idle conversation for a new fenced execution generation.
     *
     * <p>前置条件是**拒绝名单**（"不在执行中"），不再是允许名单 {@code IN ('ready','failed')}。
     * 允许名单的漏洞是静默的：新增一个可恢复态却忘了加进白名单，用户就被锁在自己的会话外面，
     * 直到他恰好打开一次那个会话才自愈。这正是 {@code AgentRunStaleReaper} 要修的那一类。</p>
     *
     * <p>{@code awaiting_approval} 仍在拒绝名单里，是**有意保留**的：有调用在等用户决定时
     * 开新意图，会让那条待决审批与它的 invocation 永久悬空（没有任何扫描会收它）。
     * 允许它需要先给"被顶掉的待决审批"一个去向，那是独立的一步，不在本批。</p>
     */
    long claim(Long conversationId, Long userId) {
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .notIn(AgentConversation::getStatus, AgentRunStatusProjection.unclaimableStatuses())
                .set(AgentConversation::getStatus, AgentRunStatusProjection.of(AgentRun.STATUS_RUNNING))
                .setSql("run_version = run_version + 1")
                .set(AgentConversation::getErrorMessage, null)
                .set(AgentConversation::getCancelRequested, 0));
        if (updated == 1) {
            AgentConversation claimed = requireOwned(conversationId, userId);
            if (claimed.getRunVersion() == null) {
                throw new IllegalStateException("智能体执行代次缺失");
            }
            return claimed.getRunVersion();
        }
        AgentConversation conversation = requireOwned(conversationId, userId);
        if (AgentRunStatusProjection.RUNNING.equals(conversation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, BUSY_MESSAGE);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, BLOCKED_MESSAGE);
    }

    /** Records a cancellation request only for the currently fenced running generation. */
    AgentConversation requestCancellation(Long conversationId, Long userId) {
        AgentConversation current = requireOwned(conversationId, userId);
        long runVersion = current.getRunVersion() == null ? 0L : current.getRunVersion();
        transactionTemplate.executeWithoutResult(status -> conversationMapper.update(
                null, new LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, AgentRunStatusProjection.RUNNING)
                        .eq(AgentConversation::getRunVersion, runVersion)
                        .set(AgentConversation::getCancelRequested, 1)));
        return requireOwned(conversationId, userId);
    }

    /** Returns the cancellation flag after verifying this worker still owns the running generation. */
    boolean isCancellationRequested(Long conversationId, Long userId, long runVersion) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())
                || !Long.valueOf(runVersion).equals(conversation.getRunVersion())
                || !AgentRunStatusProjection.RUNNING.equals(conversation.getStatus())) {
            throw new AgentRunSupersededException(conversationId, runVersion);
        }
        return Integer.valueOf(1).equals(conversation.getCancelRequested());
    }

    /** Keeps a claimed worker recoverable while model or tool calls produce no durable progress. */
    ScheduledFuture<?> startLeaseRenewal(Long conversationId, Long userId, long runVersion) {
        long staleSeconds = Math.max(60L, Math.max(1, properties.getStaleConversationMinutes()) * 60L);
        long intervalSeconds = Math.max(1L, Math.min(MAX_LEASE_RENEW_INTERVAL_SECONDS, staleSeconds / 3L));
        return agentLeaseRenewer.scheduleAtFixedRate(() -> {
            try {
                int renewed = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, AgentRunStatusProjection.RUNNING)
                        .eq(AgentConversation::getRunVersion, runVersion)
                        .setSql("updated_at = CURRENT_TIMESTAMP"));
                if (renewed != 1) {
                    throw new AgentRunSupersededException(conversationId, runVersion);
                }
            } catch (AgentRunSupersededException superseded) {
                log.debug("agent lease renewal stopped for superseded conversation {} and run {}",
                        conversationId, runVersion);
                throw superseded;
            } catch (RuntimeException exception) {
                log.warn("agent lease renewal failed for conversation {} and run {}: {}",
                        conversationId, runVersion, exception.getClass().getSimpleName());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** Moves a running generation to ready and records its durable complete event. */
    TerminalTransition complete(Long conversationId, Long userId, long runVersion) {
        return terminalTransition(conversationId, userId, runVersion, AgentRun.STATUS_COMPLETED,
                null, "complete", conversation -> {
                    executionJournal.completeRun(conversationId, userId, runVersion);
                    return eventService.storeForRun(
                            conversationId, userId, runVersion, "complete", conversation, false);
                });
    }

    /**
     * 用户请求停止并已收口。
     *
     * <p>与 {@link #complete} 分开，因为收口原因不同：以前取消路径也走 {@code complete()}，
     * 于是"用户按了停止"在 {@code agent_run.status} 里被记成 {@code COMPLETED}，
     * 与"模型正常做完"无法区分。审计与排障时这两件事必须分得开。</p>
     *
     * <p>投影上两者都是 {@code ready}（用户按完停止，下一步动作就是继续说话），
     * 所以前端契约不变。</p>
     */
    TerminalTransition cancel(Long conversationId, Long userId, long runVersion) {
        return terminalTransition(conversationId, userId, runVersion, AgentRun.STATUS_CANCELLED,
                null, "complete", conversation -> {
                    executionJournal.cancelRun(conversationId, userId, runVersion);
                    return eventService.storeForRun(
                            conversationId, userId, runVersion, "complete", conversation, false);
                });
    }

    /** Moves a running generation to failed and records its durable error event. */
    AgentEvent fail(Long conversationId, Long userId, long runVersion, String errorMessage) {
        String safeError = safeError(errorMessage);
        return terminalTransition(conversationId, userId, runVersion, AgentRun.STATUS_FAILED,
                safeError, "error", conversation -> {
                    executionJournal.failRun(conversationId, userId, runVersion, safeError);
                    return eventService.storeForRun(conversationId, userId, runVersion, "error", safeError, false);
                }).event();
    }

    /**
     * 预算耗尽（当前只有步数一种），可继续对话让模型接着收口。
     *
     * <p>投影成 {@code budget_exhausted} 而不是 {@code ready}：这一轮不是"做完了"，
     * 前端需要据此提示"因预算暂停、可以继续"。它不在 {@code claim()} 的拒绝名单里，
     * 所以用户直接说下一句就能接着走。</p>
     */
    TerminalTransition exhaustBudget(Long conversationId, Long userId, long runVersion) {
        return terminalTransition(conversationId, userId, runVersion, AgentRun.STATUS_BUDGET_EXHAUSTED,
                null, "complete", conversation -> {
                    executionJournal.exhaustBudgetRun(conversationId, userId, runVersion);
                    return eventService.storeForRun(
                            conversationId, userId, runVersion, "complete", conversation, false);
        });
    }

    /** Stops the worker lease while retaining the run and invocation for an explicit user decision. */
    AgentEvent waitForApproval(Long conversationId, Long userId, long runVersion, AgentApproval approval) {
        return transactionTemplate.execute(status -> {
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, AgentRunStatusProjection.RUNNING)
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus,
                            AgentRunStatusProjection.of(AgentRun.STATUS_WAITING_APPROVAL))
                    .set(AgentConversation::getCancelRequested, 0));
            if (updated != 1) throw new AgentRunSupersededException(conversationId, runVersion);
            executionJournal.waitRunForApproval(conversationId, userId, runVersion);
            java.util.Map<String, Object> payload = java.util.Map.of(
                    "approvalId", approval.getId(),
                    "invocationId", approval.getInvocationId(),
                    "tool", approval.getToolName(),
                    "summary", approval.getSummaryJson());
            return eventService.storeForRun(conversationId, userId, runVersion,
                    "approval_required", payload, false);
        });
    }

    /** Closes an approval-paused run after a decision has either executed or rejected its one call. */
    AgentEvent completeAfterApproval(Long conversationId, Long userId, long runVersion, Long runId,
                                     String eventType, Object payload) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, AgentRunStatusProjection.AWAITING_APPROVAL)
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus,
                            AgentRunStatusProjection.of(AgentRun.STATUS_COMPLETED))
                    .set(AgentConversation::getErrorMessage, null)
                    .set(AgentConversation::getLastCompletedAt, now));
            if (updated != 1) throw new AgentRunSupersededException(conversationId, runVersion);
            executionJournal.completeWaitingRun(runId, userId);
            eventService.storeForRun(conversationId, userId, runVersion, eventType, payload, false);
            return eventService.storeForRun(conversationId, userId, runVersion, "complete",
                    java.util.Map.of("status", AgentRunStatusProjection.READY), false);
        });
    }

    /**
     * 审批通过：先回到 {@code running}，再广播审批结果。
     *
     * <p>它是 {@link #resume} 的**特化**，不是平行实现——两者共享同一个 {@link #resumeWithin}，
     * 所以"重入同一个 Run"的条件、守卫与顺序只有一份。区别只在额外要落一条
     * {@code approval_approved} 事件，而那条事件与状态迁移在同一个事务里，避免"状态已经放行、
     * 前端却不知道审批过了"。</p>
     */
    AgentEvent resumeAfterApproval(Long conversationId, Long userId, long runVersion, Long runId,
                                   Object payload) {
        return transactionTemplate.execute(status -> {
            resumeWithin(conversationId, userId, runId);
            return eventService.storeActive(conversationId, userId, runVersion, "approval_approved", payload);
        });
    }

    /**
     * 从可恢复态重新进入**同一个** Run。
     *
     * <p><b>与 {@link #claim} 的根本区别</b>：{@code claim()} 开启**新意图**
     * （{@code run_version} +1、新 {@code intent_epoch}、{@code nextStepNo} 归 1）；
     * {@code resume()} 延续**同一意图**（{@code run_version} 不变、复用 {@code intent_epoch}、
     * {@code nextStepNo} 从 {@code max+1} 继续、{@code usageBeforeStep} 把之前的账单接回来）。
     * 混用会让"崩溃后模型重试同一个动作"被当成一件新事，于是同一个副作用被写第二遍——
     * 这正是 Level 4 要证明不发生的事。</p>
     *
     * <p>三条规则，每一条都在下面的代码里有一个具体落点：</p>
     *
     * <ul>
     *   <li><b>R-1</b>（同代次重入）：会话的 {@code run_version} 必须等于 Run 的，否则说明
     *       这一轮已经被 {@code claim()} 顶掉，那时"延续"会写进一个属于别人的代次。</li>
     *   <li><b>R-2</b>（只从可恢复态进入）：{@link AgentRun#isResumable} 之外一律拒绝，
     *       终态尤其不能——"终态不再迁移"是状态机的定义，不是策略选择。</li>
     *   <li><b>R-3</b>（{@code intent_epoch} 复用）：下面**没有任何一行**写
     *       {@code intent_epoch}，这就是机制本身。{@link AgentExecutionJournal#resumeRun}
     *       只改状态与 {@code last_running_at}，周期字段原样留着。</li>
     * </ul>
     *
     * <p>还会校验会话投影与 Run 状态一致：两者不一致说明有人绕过了
     * {@link AgentRunStatusProjection} 直接写会话状态，那种漂移必须在恢复之前响亮地失败，
     * 而不是被这次恢复顺手"修正"掉——修正会掩盖掉造成漂移的那处代码。</p>
     *
     * @return 复用后的 {@code runVersion}
     * @throws AgentRunNotResumableException 记录不存在、不属于该会话/用户、状态不可恢复、
     *                                       代次已被顶掉、投影不一致，或状态已被另一次恢复抢先迁移
     */
    long resume(Long conversationId, Long userId, Long runId) {
        Long resumed = transactionTemplate.execute(status -> resumeWithin(conversationId, userId, runId));
        if (resumed == null) {
            throw new AgentRunNotResumableException(conversationId, 0L, "执行记录不存在");
        }
        return resumed;
    }

    /**
     * {@link #resume} 的事务内主体：调用方负责事务边界。
     *
     * <p>拆出来是为了让审批特化能在**同一个**事务里紧接着落它那条事件。两处各写一遍
     * "怎么重入"就是这个类最想避免的漂移，所以条件与顺序只在这里出现一次。</p>
     */
    private long resumeWithin(Long conversationId, Long userId, Long runId) {
        AgentRun run = executionJournal.findRun(runId)
                .orElseThrow(() -> new AgentRunNotResumableException(conversationId, 0L, "执行记录不存在"));
        long runVersion = run.getRunVersion() == null ? 0L : run.getRunVersion();
        if (!conversationId.equals(run.getConversationId())) {
            throw new AgentRunNotResumableException(conversationId, runVersion, "执行记录不属于该会话");
        }
        if (!userId.equals(run.getUserId())) {
            throw new AgentRunNotResumableException(conversationId, runVersion, "执行记录不属于当前用户");
        }
        // R-2：终态在这里被挡下。"终态不再迁移"是状态机的定义，只允许后续 claim 开新 Run。
        if (!AgentRun.isResumable(run.getStatus())) {
            throw new AgentRunNotResumableException(conversationId, runVersion,
                    "当前执行状态不可恢复，只有 " + AgentRun.resumableStatuses() + " 可以继续");
        }
        AgentConversation conversation = requireOwned(conversationId, userId);
        // R-1：代次对不上说明这一轮已被新意图顶掉，"延续"会写进别人的代次。
        if (!Long.valueOf(runVersion).equals(conversation.getRunVersion())) {
            throw new AgentRunNotResumableException(conversationId, runVersion, "该执行已被新一轮顶替");
        }
        String expectedProjection = AgentRunStatusProjection.of(run.getStatus());
        if (!expectedProjection.equals(conversation.getStatus())) {
            throw new AgentRunNotResumableException(conversationId, runVersion,
                    "会话状态与执行状态不一致，拒绝在不一致的状态上恢复");
        }
        // 条件更新同时是"投影自读入以来没变过"的 CAS：另一次恢复抢先迁走时命中 0 行。
        // 清 cancel_requested 是有意的：resume 的含义就是"有人决定把这件事继续做下去"，
        // 留着陈旧的停止请求会让恢复后的第一个检查点立刻自我取消——那比不恢复更难排查。
        // 因此"用户取消过就不自动恢复"这条安全阀必须由**调用方**（reaper）在调用前判，
        // 这里分辨不出"用户点了继续"和"调度器在重试"。
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getStatus, expectedProjection)
                .eq(AgentConversation::getRunVersion, runVersion)
                .set(AgentConversation::getStatus, AgentRunStatusProjection.of(AgentRun.STATUS_RUNNING))
                .set(AgentConversation::getErrorMessage, null)
                .set(AgentConversation::getCancelRequested, 0));
        if (updated != 1) {
            throw new AgentRunNotResumableException(conversationId, runVersion, "该执行已被另一次恢复抢先继续");
        }
        // R-3：这里刻意不写 intent_epoch，复用就是"不写"。
        if (!executionJournal.resumeRun(runId, userId, run.getStatus())) {
            throw new AgentRunNotResumableException(conversationId, runVersion, "该执行已被另一次恢复抢先继续");
        }
        return runVersion;
    }

    /**
     * Recovers an expired running generation without overwriting a newer owner or generation.
     *
     * <p>Returns whether this call is the one that settled it. The stale scheduler needs that answer
     * to log a real recovery instead of re-reporting work another caller (the user opening the
     * conversation, or a previous reap cycle) already did.</p>
     *
     * <p>写 {@code INTERRUPTED} 而不是 {@code FAILED}：进程消失和业务失败是两件事，
     * 投影也不同（{@code interrupted} vs {@code failed}），前端才能区分"重试有用"和"重试也没用"。</p>
     */
    boolean recoverStale(AgentConversation conversation, Long userId) {
        if (!AgentRunStatusProjection.RUNNING.equals(conversation.getStatus())
                || conversation.getUpdatedAt() == null) {
            return false;
        }
        int staleMinutes = Math.max(1, properties.getStaleConversationMinutes());
        if (conversation.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(staleMinutes))) return false;
        // A run the user explicitly stopped, whose worker died before it could write the stop answer:
        // say "stopped" rather than dressing it up as an execution crash. This flag is also one of the
        // safety valves for the planned automatic resume, which must skip user-cancelled runs.
        boolean userCancelled = Integer.valueOf(1).equals(conversation.getCancelRequested());
        String interrupted = userCancelled ? "任务已按你的请求停止" : "上一轮执行中断，可继续对话";
        long runVersion = conversation.getRunVersion() == null ? 0L : conversation.getRunVersion();
        Boolean settled = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int changed = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversation.getId())
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, AgentRunStatusProjection.RUNNING)
                    .eq(AgentConversation::getRunVersion, conversation.getRunVersion())
                    .le(AgentConversation::getUpdatedAt, LocalDateTime.now().minusMinutes(staleMinutes))
                    .set(AgentConversation::getStatus,
                            AgentRunStatusProjection.of(AgentRun.STATUS_INTERRUPTED))
                    .set(AgentConversation::getErrorMessage, interrupted)
                    .set(AgentConversation::getLastCompletedAt, now));
            if (changed != 1) return false;
            // 传会话租约的 updated_at 而不是 now()：进程死掉到被扫描出来之间可能隔着几十分钟，
            // 用 now 结算会把这段"死掉的时间"也记成活跃时长，一次崩溃就白送一截预算。
            // 租约续期每 min(60s, stale/3) 触碰一次这个时钟，所以它是存活时刻的紧上界。
            executionJournal.interruptRun(conversation.getId(), userId, runVersion, interrupted,
                    conversation.getUpdatedAt());
            if (eventService.latestTerminal(conversation.getId(), runVersion) == null) {
                eventService.storeForRun(conversation.getId(), userId, runVersion, "error", interrupted, false);
            }
            return true;
        });
        return Boolean.TRUE.equals(settled);
    }

    /**
     * 一次"运行终态 + 会话投影"的成对迁移。
     *
     * <p>两列在同一个事务里一起改，投影值只能由 {@link AgentRunStatusProjection#of} 算出来。
     * 前置条件固定是"会话仍在本轮的 {@code running}"——除了
     * {@link #completeAfterApproval} / {@link #resumeAfterApproval} 那两个审批特化，
     * 它们从 {@code awaiting_approval} 出发，所以自己写。</p>
     *
     * @param journalTransition 运行侧的事实落库与事件落库，返回要投递的事件
     */
    private TerminalTransition terminalTransition(Long conversationId, Long userId, long runVersion,
                                                 String runStatus, String errorMessage,
                                                 String eventType,
                                                 java.util.function.Function<AgentConversation, AgentEvent> journalTransition) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            AgentConversation current = requireOwned(conversationId, userId);
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, AgentRunStatusProjection.RUNNING)
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus, AgentRunStatusProjection.of(runStatus))
                    .set(AgentConversation::getErrorMessage, errorMessage)
                    .set(AgentConversation::getCancelRequested, 0)
                    .set(AgentConversation::getLastCompletedAt, now));
            if (updated != 1) throw new AgentRunSupersededException(conversationId, runVersion);
            current.setStatus(AgentRunStatusProjection.of(runStatus));
            current.setErrorMessage(errorMessage);
            current.setCancelRequested(0);
            current.setLastCompletedAt(now);
            AgentEvent event = journalTransition.apply(current);
            return new TerminalTransition(current, event);
        });
    }

    private AgentConversation requireOwned(Long conversationId, Long userId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw new AgentRunSupersededException(conversationId, 0L);
        }
        return conversation;
    }

    private String safeError(String errorMessage) {
        return limit(errorMessage == null || errorMessage.isBlank()
                ? "智能体任务失败，请稍后重试" : errorMessage, 1000);
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

/** Durable terminal state and event returned to the facade for billing and live fan-out. */
record TerminalTransition(AgentConversation conversation, AgentEvent event) {
}
