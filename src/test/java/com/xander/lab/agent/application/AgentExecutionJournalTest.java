package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.domain.RunBudget;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import com.xander.lab.agent.infrastructure.AgentStepMapper;
import com.xander.lab.agent.infrastructure.AgentToolInvocationMapper;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the pre-side-effect facts written by the Phase 1 execution journal. */
class AgentExecutionJournalTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentConversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentRun.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentStep.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentToolInvocation.class);
    }

    @Test
    void createsRunStepAndInvocationBeforeTheToolCanExecute() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentStepMapper stepMapper = mock(AgentStepMapper.class);
        AgentToolInvocationMapper invocationMapper = mock(AgentToolInvocationMapper.class);
        AgentConversation conversation = runningConversation();
        AgentRun run = runningRun();
        when(conversationMapper.selectById(7L)).thenReturn(conversation);
        when(runMapper.selectOne(any())).thenReturn(null, run);
        when(runMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<AgentRun>getArgument(0).setId(17L);
            return 1;
        });
        when(stepMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<AgentStep>getArgument(0).setId(23L);
            return 1;
        });
        when(invocationMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<AgentToolInvocation>getArgument(0).setId(29L);
            return 1;
        });

        AgentExecutionJournal journal = new AgentExecutionJournal(
                conversationMapper, runMapper, stepMapper, invocationMapper, mock(BillingService.class));
        journal.startRun(7L, 42L, 3L, RunBudget.unlimited());
        AgentStep step = journal.startStep(7L, 42L, 3L, 1,
                "{\"contextChars\":120,\"toolSetHash\":\"abcd\"}");
        AgentToolInvocation tool = journal.startInvocation(step, "00000000-0000-0000-0000-000000000001",
                "publish_post", JsonNodeFactory.instance.objectNode().put("postId", 99));

        verify(runMapper).insert(org.mockito.ArgumentMatchers.argThat(inserted ->
                inserted.getConversationId().equals(7L)
                        && inserted.getRunVersion().equals(3L)
                        && AgentRun.STATUS_RUNNING.equals(inserted.getStatus())));
        verify(stepMapper).insert(org.mockito.ArgumentMatchers.argThat(inserted ->
                inserted.getRunId().equals(17L)
                        && inserted.getStepNo().equals(1)
                        && AgentStep.STATUS_RUNNING.equals(inserted.getStatus())));
        verify(invocationMapper).insert(org.mockito.ArgumentMatchers.argThat(inserted ->
                inserted.getStepId().equals(23L)
                        && "00000000-0000-0000-0000-000000000001".equals(inserted.getInvocationId())
                        && "publish_post".equals(inserted.getToolName())
                        && AgentToolInvocation.STATUS_RUNNING.equals(inserted.getStatus())));

        when(stepMapper.update(any(), any())).thenReturn(1);
        journal.completeStep(step, ModelCallResult.of(
                JsonNodeFactory.instance.objectNode().put("done", false), new ModelUsage(10, 2, 5), "test-model"));
        verify(stepMapper).update(any(), any());
        assertThat(tool.getId()).isEqualTo(29L);
    }

    @Test
    void marksInterruptedChildrenWhenTheRunFails() {
        Fixture fixture = Fixture.create();
        when(fixture.invocationMapper.update(any(), any())).thenReturn(1);

        fixture.journal.failRun(7L, 42L, 3L, "worker crashed");

        verify(fixture.stepMapper).update(any(), any());
        List<LambdaUpdateWrapper<AgentToolInvocation>> updates = fixture.capturedInvocationUpdates();
        assertThat(updates).hasSize(2);
        // 有副作用身份、且不是 PREPARED 的判 UNKNOWN，交给对账；其余判终态。
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_UNKNOWN).getSqlSegment())
                .contains("effect_id IS NOT NULL").contains("effect_state <>");
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_FAILED).getSqlSegment())
                .contains("effect_id IS NULL").contains("effect_state =");
    }

    @Test
    void cancellingARunNowSettlesItsInFlightInvocationsByEffectState() {
        // B7 的关键行为变化。此前取消路径**完全不动** invocation，理由留给了执行管道；
        // 但管道在协作式取消点之后可能已经被 kill，于是它们会永久停在 RUNNING——
        // §1.3 那个"取消落在写已提交之后"的重复写窗口就是这样一直开着的。
        Fixture fixture = Fixture.create();
        when(fixture.invocationMapper.update(any(), any())).thenReturn(1);

        fixture.journal.cancelRun(7L, 42L, 3L);

        verify(fixture.stepMapper).update(any(), any());
        List<LambdaUpdateWrapper<AgentToolInvocation>> updates = fixture.capturedInvocationUpdates();
        assertThat(updates).hasSize(2);
        // 已派发/已确认的必须判 UNKNOWN 而不是 CANCELLED：写可能已经到远端了，
        // 标成"已取消"会让对账器永远不去查它，用户重发就是第二次写入。
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_UNKNOWN).getSqlSegment())
                .contains("effect_id IS NOT NULL").contains("effect_state <>");
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_CANCELLED).getSqlSegment())
                .contains("effect_id IS NULL").contains("effect_state =");
    }

    @Test
    void anInvocationThatHasAnEffectButNeverRecordedAStageIsStillReconciled() {
        // 存量行的情形：阶段列是 B7 才加的，所以 B6 与 B7 之间那个部署窗口里创建的 invocation
        // 会停在默认的 NONE——而那时请求可能已经发出去了。它必须进对账，不能被当成"没发出"
        // 而静默终结。这条钉住的正是"判据必须带上 effect_id，不能只看阶段值"：
        // 同样停在 NONE，有 effect_id 的进对账，没有的（只读）才被安全终结。
        Fixture fixture = Fixture.create();
        when(fixture.invocationMapper.update(any(), any())).thenReturn(1);

        fixture.journal.cancelRun(7L, 42L, 3L);

        List<LambdaUpdateWrapper<AgentToolInvocation>> updates = fixture.capturedInvocationUpdates();
        LambdaUpdateWrapper<AgentToolInvocation> reconcile =
                updateSetting(updates, AgentToolInvocation.STATUS_UNKNOWN);
        assertThat(reconcile.getSqlSegment()).contains("effect_id IS NOT NULL");
        // 判据里不能出现 NONE：一旦按阶段值把 NONE 当成"安全"，存量行就漏了。
        assertThat(reconcile.getParamNameValuePairs().values())
                .doesNotContain(AgentToolInvocation.EFFECT_STATE_NONE);
        // 只读工具走另一侧：它压根没有远端写，丢进对账只会送去一次纯属虚构的查询。
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_CANCELLED).getSqlSegment())
                .contains("effect_id IS NULL");
    }

    @Test
    void theTwoSettlementUpdatesAreDisjointAndLeaveNoResidualRunning() {
        // 两条 UPDATE 必须互斥且合起来穷尽，否则会留下永久 RUNNING 的行（D4 要断"没有残留 RUNNING"）。
        // 互斥靠"一条要求 effect_id 非空、另一条以 effect_id IS NULL 起头"；穷尽靠另一条还包含
        // "effect_state = PREPARED"，于是"有 effect_id 且 PREPARED"归它、其余归第一条。
        Fixture fixture = Fixture.create();
        when(fixture.invocationMapper.update(any(), any())).thenReturn(1);

        fixture.journal.cancelRun(7L, 42L, 3L);

        List<LambdaUpdateWrapper<AgentToolInvocation>> updates = fixture.capturedInvocationUpdates();
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_UNKNOWN).getSqlSegment())
                .contains("effect_id IS NOT NULL");
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_CANCELLED).getSqlSegment())
                .contains("effect_id IS NULL");
        for (LambdaUpdateWrapper<AgentToolInvocation> update : updates) {
            // 两条都只收在途行，且都带 effect_state 判据——少了 status 会改写已终结的历史。
            assertThat(update.getSqlSegment()).contains("status").contains("effect_state");
            assertThat(update.getParamNameValuePairs().values())
                    .contains(AgentToolInvocation.STATUS_RUNNING);
        }
    }

    @Test
    void theSettlementPredicatesAreParenthesisedInTheRenderedSql() {
        // 上一条用例断的是"子串在不在"，而这里有一个**静默且致命**的失效模式：
        // 第二条 UPDATE 的条件是 .and(w -> w.isNull(effectId).or().eq(effectState, PREPARED))。
        // 若这层 .and(...) 渲染时丢了括号，SQL 就成了
        //     run_id = ? AND status = ? AND effect_id IS NULL OR effect_state = ?
        // 按 AND 优先级解析为 (run_id AND status AND effect_id IS NULL) OR (effect_state = ?)，
        // 于是**别的 run 里任何非 PREPARED 的 invocation 都会被一起结算**——跨 run 改写，
        // 而子串断言对它完全无感（"effect_id IS NULL" 确实在里面）。所以必须钉住"括号真的在"。
        Fixture fixture = Fixture.create();
        when(fixture.invocationMapper.update(any(), any())).thenReturn(1);

        fixture.journal.cancelRun(7L, 42L, 3L);

        List<LambdaUpdateWrapper<AgentToolInvocation>> updates = fixture.capturedInvocationUpdates();
        String reconcile = updateSetting(updates, AgentToolInvocation.STATUS_UNKNOWN).getSqlSegment();
        String terminal = updateSetting(updates, AgentToolInvocation.STATUS_CANCELLED).getSqlSegment();

        // ① 对账那条是**纯合取**：effect_id 非空 且 阶段不是 PREPARED，中间不允许出现 OR。
        assertThat(reconcile).contains("effect_id IS NOT NULL").contains("effect_state <>");
        assertThat(reconcile).doesNotContain(" OR ");

        // ② 终结那条的 OR 必须被括号包住，且括号紧跟在 AND 之后——这是本条用例存在的理由。
        assertThat(terminal).contains("(effect_id IS NULL OR effect_state =");

        // ③ 两条都要用 status 收窄到在途行，否则会改写已经终结的历史。
        assertThat(reconcile).contains("status =");
        assertThat(terminal).contains("status =");
    }

    @Test
    void aNormallyCompletedRunDoesNotRewriteItsInvocations() {
        // 正常退出不该有在途记录；真出现了说明编排循环漏收口，这里只记日志不代它收——
        // 静默补收会把一个真实的循环缺陷藏起来。
        Fixture fixture = Fixture.create();

        fixture.journal.completeRun(7L, 42L, 3L);

        verify(fixture.invocationMapper, times(0)).update(any(), any());
    }

    // ---------- 副作用阶段（B7） ----------

    @Test
    void attachingAnEffectAlsoMarksTheInvocationPrepared() {
        // "接上 Effect"与"进入 PREPARED"是同一个事实的两面：有副作用身份就意味着"意图已落库、
        // 请求还没交给工具"。分两处写迟早会漏一处，而漏掉的表现是这次调用永远停在 NONE，
        // 于是崩溃收口把它当成"没有副作用"直接判 FAILED——正是 B7 要消灭的那个漏判。
        Fixture fixture = Fixture.create();
        AgentToolInvocation invocation = invocationWithEffect(null);

        fixture.journal.attachEffect(invocation, "effect-1");

        LambdaUpdateWrapper<AgentToolInvocation> update = fixture.capturedInvocationUpdate();
        assertThat(update.getSqlSet()).contains("effect_id").contains("effect_state");
        assertThat(update.getParamNameValuePairs().values())
                .contains("effect-1", AgentToolInvocation.EFFECT_STATE_PREPARED);
        // 内存对象也要同步：completeInvocation 拿的是同一个对象，只写库会让成功路径漏掉结算。
        assertThat(invocation.getEffectId()).isEqualTo("effect-1");
        assertThat(invocation.getEffectState()).isEqualTo(AgentToolInvocation.EFFECT_STATE_PREPARED);
    }

    @Test
    void markingDispatchIsGuardedOnPreparedSoItIsIdempotent() {
        Fixture fixture = Fixture.create();
        AgentToolInvocation invocation = invocationWithEffect("effect-1");

        fixture.journal.markEffectDispatched(invocation);

        LambdaUpdateWrapper<AgentToolInvocation> update = fixture.capturedInvocationUpdate();
        assertThat(update.getSqlSegment()).contains("id").contains("effect_state");
        assertThat(update.getParamNameValuePairs().values())
                .contains(29L, AgentToolInvocation.EFFECT_STATE_PREPARED,
                        AgentToolInvocation.EFFECT_STATE_DISPATCHED);
        assertThat(update.getSqlSet()).contains("effect_state").contains("effect_dispatched_at");
        assertThat(invocation.getEffectState()).isEqualTo(AgentToolInvocation.EFFECT_STATE_DISPATCHED);
    }

    @Test
    void markingConfirmationAcceptsPreparedOrDispatchedSoAMissedDispatchMarkIsRepaired() {
        // 守卫刻意放宽：若某个执行点漏了 markEffectDispatched，严格守卫会让这次调用停在 PREPARED
        // 而状态却是 SUCCEEDED——一个自相矛盾的行。真正的危险方向是"发出去了却记成没发出"，
        // 那只发生在崩溃路径上（成功路径最终一定会走到这里）。
        Fixture fixture = Fixture.create();
        AgentToolInvocation invocation = invocationWithEffect("effect-1");

        fixture.journal.markEffectConfirmed(invocation);

        LambdaUpdateWrapper<AgentToolInvocation> update = fixture.capturedInvocationUpdate();
        assertThat(update.getSqlSegment()).contains("id").contains("effect_state");
        assertThat(update.getParamNameValuePairs().values())
                .contains(29L, AgentToolInvocation.EFFECT_STATE_PREPARED,
                        AgentToolInvocation.EFFECT_STATE_DISPATCHED,
                        AgentToolInvocation.EFFECT_STATE_CONFIRMED);
        assertThat(update.getSqlSet()).contains("effect_confirmed_at");
        assertThat(invocation.getEffectState()).isEqualTo(AgentToolInvocation.EFFECT_STATE_CONFIRMED);
    }

    @Test
    void aReadOnlyInvocationIsNeverMarkedDispatched() {
        // 只读工具没有副作用阶段。给它写 DISPATCHED 会让崩溃收口把一次纯读当成待对账的写。
        Fixture fixture = Fixture.create();
        AgentToolInvocation readOnly = invocationWithEffect(null);

        fixture.journal.markEffectDispatched(readOnly);
        fixture.journal.markEffectConfirmed(readOnly);

        verify(fixture.invocationMapper, times(0)).update(any(), any());
        assertThat(readOnly.getEffectState()).isNull();
    }

    @Test
    void completingAnInvocationConfirmsTheEffectBeforeSettlingIt() {
        // 因果顺序就是这个顺序：先记"拿到远端凭据"，再结算 Effect。反过来写会让一条 CONFIRMED
        // 的调用挂在一个已经 SETTLED 的 Effect 上。
        Fixture fixture = Fixture.create();
        AgentToolInvocation invocation = invocationWithEffect("effect-1");

        fixture.journal.completeInvocation(invocation,
                JsonNodeFactory.instance.objectNode().put("ok", true));

        List<LambdaUpdateWrapper<AgentToolInvocation>> updates = fixture.capturedInvocationUpdates();
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_SUCCEEDED)).isNotNull();
        assertThat(updateSetting(updates, AgentToolInvocation.EFFECT_STATE_CONFIRMED)).isNotNull();
        InOrder order = inOrder(fixture.invocationMapper, fixture.effectService);
        order.verify(fixture.invocationMapper, times(2)).update(any(), any());
        order.verify(fixture.effectService).settleEffect("effect-1");
    }

    @Test
    void interruptingARunSendsOnlyDispatchedWorkToReconciliation() {
        // D6（kill -9 恢复）的判据是"崩溃后查库：只有确实可能发出去了的才是 UNKNOWN"。
        // 这条路径走的是 interruptRun（reaper 恢复），不是 failRun——两条出口必须按**同一判据**
        // 分流，否则同一场崩溃从不同入口收口会得到不同的数据。
        Fixture fixture = Fixture.create();
        when(fixture.invocationMapper.update(any(), any())).thenReturn(1);

        fixture.journal.interruptRun(7L, 42L, 3L, "worker vanished",
                LocalDateTime.now().minusMinutes(5));

        List<LambdaUpdateWrapper<AgentToolInvocation>> updates = fixture.capturedInvocationUpdates();
        assertThat(updates).hasSize(2);
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_UNKNOWN).getSqlSegment())
                .contains("effect_id IS NOT NULL").contains("effect_state <>");
        assertThat(updateSetting(updates, AgentToolInvocation.STATUS_FAILED).getSqlSegment())
                .contains("effect_id IS NULL");
    }

    // ---------- 副作用身份在收口路径上的存活（D1 的两半） ----------

    @Test
    void cancellingAnInvocationNeverSettlesItsBusinessEffect() {
        // **D1 的命门。** 被取消的那次尝试如果顺手把 Effect 结算掉（SETTLED），后续重试就
        // **采纳不到**它、只能铸新键，下游唯一键不再命中——用户重发就是第二次写入，而 D1 正是
        // 要证明这件事不发生。所以取消只结算**这次调用**，不结算**这个业务动作**：
        // "这次执行停了"不等于"这件事不做了"。
        //
        // 这条特别值得钉，因为"取消时顺手清理一下副作用"是一个看起来完全合理的改动方向，
        // 而它会把 D1 直接打回 Fail，且不会有任何报错。
        Fixture fixture = Fixture.create();
        AgentToolInvocation invocation = invocationWithEffect("effect-1");
        when(fixture.invocationMapper.update(any(), any())).thenReturn(1);

        fixture.journal.cancelInvocation(invocation);

        // 这次调用本身确实被结算了……
        assertThat(fixture.capturedInvocationUpdate().getParamNameValuePairs().values())
                .contains(AgentToolInvocation.STATUS_CANCELLED);
        // ……但业务动作没有被结算，也**没有**被放弃——放弃等于"用户明确说不要做这件事"，
        // 而取消只是"这一轮别跑了"。两者混同会让一次取消永久毒掉这个动作。
        verify(fixture.effectService, never()).settleEffect(any());
        verify(fixture.effectService, never()).abandonEffect(any());
    }

    @Test
    void reconcilingAnInvocationSettlesItsEffectSoItIsNotAdoptedAgain() {
        // 上一条的另一半：对账证明"远端确实有这次写"之后，这个业务动作**已经发生**，
        // 就不该再被采纳——否则用户真的要做的第二次相同动作会被当成重试接上旧键，下游把它吞掉
        // （"用户要发两条提醒、只发出一条"）。两个方向都不出错，D1 才算真的稳。
        Fixture fixture = Fixture.create();
        AgentToolInvocation invocation = invocationWithEffect("effect-1");
        when(fixture.invocationMapper.update(any(), any())).thenReturn(1);

        fixture.journal.reconcileInvocation(invocation, JsonNodeFactory.instance.objectNode());

        verify(fixture.effectService).settleEffect("effect-1");
    }

    /** 找出把某个值写进 SET 的那条 UPDATE；两条收口语句靠 SET 的取值区分。 */
    private static LambdaUpdateWrapper<AgentToolInvocation> updateSetting(
            List<LambdaUpdateWrapper<AgentToolInvocation>> updates, String value) {
        return updates.stream()
                .filter(update -> update.getParamNameValuePairs().containsValue(value))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到把值设为 " + value + " 的那条 UPDATE"));
    }

    /** 一套接好线的 journal 与它用到的 mapper，省得每个用例重复十行 mock 设置。 */
    private record Fixture(AgentExecutionJournal journal, AgentStepMapper stepMapper,
                           AgentToolInvocationMapper invocationMapper, AgentEffectService effectService) {

        static Fixture create() {
            AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
            AgentRunMapper runMapper = mock(AgentRunMapper.class);
            AgentStepMapper stepMapper = mock(AgentStepMapper.class);
            AgentToolInvocationMapper invocationMapper = mock(AgentToolInvocationMapper.class);
            AgentEffectService effectService = mock(AgentEffectService.class);
            when(conversationMapper.selectById(7L)).thenReturn(runningConversation());
            when(runMapper.selectOne(any())).thenReturn(runningRun());
            when(runMapper.update(any(), any())).thenReturn(1);
            // 用带 effectService 的那个构造器：结算副作用的时序只在它非空时才走得通，
            // 而"先确认、后结算"的顺序正是 B7 要钉的东西之一。
            return new Fixture(new AgentExecutionJournal(conversationMapper, runMapper, stepMapper,
                    invocationMapper, mock(BillingService.class), effectService),
                    stepMapper, invocationMapper, effectService);
        }

        /**
         * 捕获收口时发给 invocation 的两条 UPDATE。
         *
         * <p>返回前先把 SQL 渲染一次：MyBatis-Plus 的 {@code paramNameValuePairs} 是惰性填充的，
         * 不渲染就读到空 Map，断言会以"找不到某个值"的形式失败，看起来像被测代码漏了条件。</p>
         */
        List<LambdaUpdateWrapper<AgentToolInvocation>> capturedInvocationUpdates() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<LambdaUpdateWrapper<AgentToolInvocation>> captor =
                    ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(invocationMapper, times(2)).update(any(), captor.capture());
            List<LambdaUpdateWrapper<AgentToolInvocation>> updates = captor.getAllValues();
            for (LambdaUpdateWrapper<AgentToolInvocation> update : updates) {
                update.getSqlSet();
                update.getSqlSegment();
            }
            return updates;
        }

        /** 捕获唯一一条发给 invocation 的 UPDATE。 */
        LambdaUpdateWrapper<AgentToolInvocation> capturedInvocationUpdate() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<LambdaUpdateWrapper<AgentToolInvocation>> captor =
                    ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            verify(invocationMapper).update(any(), captor.capture());
            LambdaUpdateWrapper<AgentToolInvocation> update = captor.getValue();
            update.getSqlSet();
            update.getSqlSegment();
            return update;
        }
    }

    private static AgentToolInvocation invocationWithEffect(String effectId) {
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(29L);
        invocation.setInvocationId("invocation-29");
        invocation.setEffectId(effectId);
        return invocation;
    }

    private static AgentConversation runningConversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(42L);
        conversation.setRunVersion(3L);
        conversation.setStatus("running");
        return conversation;
    }

    private static AgentRun runningRun() {
        AgentRun run = new AgentRun();
        run.setId(17L);
        run.setUserId(42L);
        run.setConversationId(7L);
        run.setRunVersion(3L);
        run.setStatus(AgentRun.STATUS_RUNNING);
        return run;
    }
}
