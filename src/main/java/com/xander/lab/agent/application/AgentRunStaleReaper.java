package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底回收"持有者进程已经消失、没人再碰过"的 running 执行，并把它**自动接回去**。
 *
 * <p><b>为什么需要它。</b>{@link AgentRunLifecycle#recoverStale} 的调用点只有"用户打开会话"这一条路径
 * （{@code AgentConversationService#get} 与列表查询）。{@code kill -9} 之后租约续期线程随进程一起死，
 * 没有任何东西再触碰 {@code updated_at}，于是执行永久停在 {@code running}：
 * {@link AgentRunLifecycle#claim} 会以"智能体正在执行中，请稍候"拒绝新一轮，
 * 用户被锁在自己的会话外面，直到他恰好打开一次那个会话才自愈。项目里
 * {@code BlogContentPlanScheduler}、{@code RecitationWorker} 都有各自的调度兜底，这一处此前没有。</p>
 *
 * <p><b>与对账器同构</b>：批量扫描、条件更新、幂等、单条失败不中断整批
 * （见 {@link AgentToolInvocationReconciler}）。真正的收敛动作委托给
 * {@link AgentRunLifecycle#recoverStale}，本类不自己写状态——否则"什么算超时"会有两份实现，
 * 迟早漂移。</p>
 *
 * <p><b>为什么崩溃后要自动继续，而不是等用户点"继续"。</b>如果只提供按钮，幂等的正确性就依赖
 * 前端实现 + 用户点对按钮。考虑最常见的真实路径：Agent 崩溃 → 用户回来 → 在输入框打"继续"
 * → {@code claim()} → **新 {@code intent_epoch}** → 上一轮已经发出但没确认的副作用无法被识别为
 * 重试 → 可能重复写。自动 resume 走的是 {@link AgentRunLifecycle#resume}，{@code run_version}
 * 不变、{@code intent_epoch} 复用，于是"模型重试同一个动作"落在同一周期里，可以被识别为重试。
 * 这是 Level 4 清单里的 "Agent restart recovery"，也是让 {@code intent_epoch} 不依赖前端配合的
 * 唯一办法。</p>
 *
 * <p><b>三条安全阀</b>（缺一条都可能让调度器变成故障放大器）：</p>
 *
 * <ol>
 *   <li><b>每个 Run 最多自动 resume 一次</b>：靠 {@code agent_run.auto_resumed_at} 的一次性 CAS
 *       实现（见 {@link AgentExecutionJournal#markAutoResumed}），并且**先记账再提交**。再崩就
 *       只标记不再自动重试——一个依赖坏工具的 Run 会被每个扫描周期捞起来重跑，那正是最需要被
 *       限制的故障。第二次崩溃仍然记 {@code INTERRUPTED} 而不是 {@code FAILED}：可恢复性是事实，
 *       把它写成失败会让用户失去"继续"这个正确的出路，而"已自动恢复过"这件事已经由
 *       {@code auto_resumed_at} 非空表达了。</li>
 *   <li><b>用户显式取消过的 Run 不自动恢复</b>：{@code cancel_requested} 是用户的意志，调度器
 *       不能替他撤回。注意这个判断必须在**调用 {@code resume()} 之前**做——
 *       {@code resume()} 会清掉这个标志（它分不清"用户点了继续"和"调度器在重试"），
 *       所以一旦漏判，取消过的 Run 会被静默复活。</li>
 *   <li><b>走 {@code resume()} 而不是 {@code claim()}</b>：见上文的幂等理由。</li>
 * </ol>
 *
 * <p>迁移之前创建的 Run 没有 {@code intent_epoch}，自动恢复它们等于把它们塞进一个凭猜测编出来的
 * 周期里，所以这一类只标记、不恢复。</p>
 */
@Service
public class AgentRunStaleReaper {
    private static final Logger log = LoggerFactory.getLogger(AgentRunStaleReaper.class);
    private static final int BATCH_SIZE = 50;

    private final AgentRunMapper runMapper;
    private final AgentConversationMapper conversationMapper;
    private final AgentRunLifecycle runLifecycle;
    private final AgentConversationProperties properties;
    /**
     * 用 {@link ObjectProvider} 而不是直接注入：{@code AgentConversationService} 的依赖链很长，
     * 而本类只需要它一个方法。这也让"没有可用工作线程时只标记不恢复"的降级路径自然成立。
     */
    private final ObjectProvider<AgentConversationService> conversationServiceProvider;
    private final TaskExecutor agentTaskExecutor;
    /** 只为自动恢复的一次性名额 CAS；为 {@code null} 时本类退化成"只收敛、不恢复"。 */
    private final AgentExecutionJournal executionJournal;

    /** 兼容构造器：给只验证"扫谁、交给谁"的聚焦测试用，自动恢复降级为只标记。 */
    public AgentRunStaleReaper(AgentRunMapper runMapper,
                               AgentConversationMapper conversationMapper,
                               AgentRunLifecycle runLifecycle,
                               AgentConversationProperties properties) {
        this(runMapper, conversationMapper, runLifecycle, properties, null, null, null);
    }

    @Autowired
    public AgentRunStaleReaper(AgentRunMapper runMapper,
                               AgentConversationMapper conversationMapper,
                               AgentRunLifecycle runLifecycle,
                               AgentConversationProperties properties,
                               ObjectProvider<AgentConversationService> conversationServiceProvider,
                               @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
                               AgentExecutionJournal executionJournal) {
        this.runMapper = runMapper;
        this.conversationMapper = conversationMapper;
        this.runLifecycle = runLifecycle;
        this.properties = properties;
        this.conversationServiceProvider = conversationServiceProvider;
        this.agentTaskExecutor = agentTaskExecutor;
        this.executionJournal = executionJournal;
    }

    /** 周期性回收超时未续租的执行；单条失败只记账，不影响整批。 */
    @Scheduled(fixedDelayString = "${agent-harness.stale-reap-delay-ms:60000}")
    public void reapStaleRuns() {
        int staleMinutes = Math.max(1, properties.getStaleConversationMinutes());
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleMinutes);
        List<AgentRun> candidates = runMapper.selectStaleRunningRuns(cutoff, BATCH_SIZE);
        for (AgentRun run : candidates) {
            try {
                reap(run, staleMinutes);
            } catch (RuntimeException exception) {
                // 一条失败不能拖垮整批：留着下个周期再收，比让后面几十条一直卡在 running 里强。
                log.error("agent stale run reap failed conversation={} runId={} runVersion={}"
                                + " exceptionType={} message={}",
                        run.getConversationId(), run.getId(), run.getRunVersion(),
                        exception.getClass().getName(), exception.getMessage(), exception);
            }
        }
    }

    /**
     * 收敛单条候选。
     *
     * <p>候选集是"可能超时"，不是"一定超时"：{@link AgentRunMapper#selectStaleRunningRuns} 按执行记录
     * 过滤，而超时的权威判据在会话那一行的租约上。所以这里必须重新读一次会话，
     * 由 {@link AgentRunLifecycle#recoverStale} 自己再判一次——它读到的租约已经是新鲜的，
     * 就会原样返回 false，不产生任何写操作。</p>
     */
    private void reap(AgentRun run, int staleMinutes) {
        if (run.getConversationId() == null) return;
        AgentConversation conversation = conversationMapper.selectById(run.getConversationId());
        if (conversation == null || conversation.getUserId() == null) return;
        // 取消标志要在 recoverStale 之前读：它读的是同一行，但 recoverStale 之后这一行的语义已经
        // 从"正在执行"变成"已中断"，而我们要的是"用户在他还活着的时候有没有按过停止"。
        boolean userCancelled = Integer.valueOf(1).equals(conversation.getCancelRequested());
        if (!runLifecycle.recoverStale(conversation, conversation.getUserId())) return;
        log.warn("agent stale run reaped conversation={} runId={} runVersion={} staleMinutes={}"
                        + " userCancelled={}",
                conversation.getId(), run.getId(), run.getRunVersion(), staleMinutes, userCancelled);
        autoResume(run, conversation, userCancelled);
    }

    /**
     * 把刚被标记为中断的 Run 接回去，除非某条安全阀说不。
     *
     * <p>安全阀的判定顺序有意如此：**先判"该不该"，再记账，最后才提交**。记账（
     * {@link AgentExecutionJournal#markAutoResumed}）是一次性 CAS，放在提交之前——写在后头的话，
     * "提交成功但记账失败"会让这个 Run 每个扫描周期都被再恢复一次，而"最多一次"正是防止一个
     * 必然失败的 Run 无限重跑的唯一屏障。</p>
     */
    private void autoResume(AgentRun run, AgentConversation conversation, boolean userCancelled) {
        Long userId = conversation.getUserId();
        long runVersion = run.getRunVersion() == null ? 0L : run.getRunVersion();
        if (userCancelled) {
            log.info("agent stale run not auto-resumed because the user stopped it conversation={} runId={}",
                    conversation.getId(), run.getId());
            return;
        }
        if (run.getIntentEpoch() == null) {
            log.info("agent stale run not auto-resumed because it predates intent epochs conversation={} runId={}",
                    conversation.getId(), run.getId());
            return;
        }
        if (run.getAutoResumedAt() != null) {
            log.warn("agent stale run already auto-resumed once, leaving it interrupted for the user"
                            + " conversation={} runId={} autoResumedAt={}",
                    conversation.getId(), run.getId(), run.getAutoResumedAt());
            return;
        }
        if (conversationServiceProvider == null || agentTaskExecutor == null || executionJournal == null) {
            log.info("agent stale run left interrupted because no worker pool is wired conversation={} runId={}",
                    conversation.getId(), run.getId());
            return;
        }
        if (!executionJournal.markAutoResumed(run.getId(), userId)) {
            // 拿不到名额说明另一个实例已经恢复了它。重复提交会让同一个 Run 被两个 worker 同时跑。
            log.info("agent stale run auto-resume quota not acquired conversation={} runId={}",
                    conversation.getId(), run.getId());
            return;
        }
        // 注意不要拿候选行自己的 status 去判可恢复性：候选行是在 recoverStale 之前读的，
        // 它的 status 还是 RUNNING，而刚刚落下去的是 INTERRUPTED。resume() 内部会按 id
        // 重新读一次权威行，所以这里直接把 id 交出去就是对的——多读一次只会多一次无用查询。
        try {
            runLifecycle.resume(conversation.getId(), userId, run.getId());
        } catch (AgentRunNotResumableException | AgentRunSupersededException refused) {
            // 不可恢复不是错误，是一个需要被记录下来的业务结果：状态可能在两次读之间又被谁改过。
            log.warn("agent stale run not auto-resumed conversation={} runId={} reason={}",
                    conversation.getId(), run.getId(), refused.getMessage());
            return;
        }
        try {
            // 空监听器是刻意的：这条恢复没有客户端在等，事件由 storeForRun/appendActive 落库，
            // 用户下次打开会话时从库里重放。
            AgentConversationService service = conversationServiceProvider.getObject();
            agentTaskExecutor.execute(() -> service.executeClaimedTurn(
                    conversation.getId(), userId, runVersion, null, false, ignored -> { }));
        } catch (RuntimeException rejected) {
            // 提交被拒（线程池满）或拿不到服务都不代表这次执行失败：Run 已经是 RUNNING，
            // 配额也已经花掉。落一条日志就够了，把它写成 FAILED 反而会掩盖"其实还能继续"这个事实。
            log.warn("agent stale run auto-resume could not be handed to a worker conversation={} runId={}"
                            + " message={}",
                    conversation.getId(), run.getId(), rejected.getMessage());
        }
    }
}
