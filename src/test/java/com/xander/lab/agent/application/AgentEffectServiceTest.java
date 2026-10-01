package com.xander.lab.agent.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentEffect;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentEffectMapper;
import com.xander.lab.agent.infrastructure.AgentToolInvocationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Effect 的采纳规则。
 *
 * <p>采纳是 B6 的核心：它决定"模型重新发起同一个调用"到底被下游认成重试、还是一件新事。
 * 判错的两个方向代价不对称——**过度采纳会静默吞掉用户真的想做的第二个动作**（用户说要发两条提醒，
 * 结果只发出一条），**过度铸造则让重试带上新键、重复写**（D1 要证明不发生的事）。</p>
 *
 * <p>判据有四条（周期、工具、指纹、更早的步）加一条状态限制。其中三条由 SQL 承担，所以本类用
 * **捕获 wrapper 并检查 WHERE 子句**的方式钉它们——只 stub 返回值的话，"忘了加 {@code status = OPEN}
 * 过滤"这种错误会完全测不出来（mock 照样返回我让它返回的东西）。这正是守卫比返回值更值得钉的原因。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentEffectServiceTest {

    private static final long USER_ID = 42L;
    private static final long CONVERSATION_ID = 7L;
    private static final long RUN_ID = 17L;
    private static final String INTENT_EPOCH = "6f1c0a44-1f0e-4a3c-9a2f-6b7c1d2e3f40";
    private static final String TOOL = "email_reminder";
    private static final int STEP_NO = 4;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private AgentEffectMapper effectMapper;

    @Mock
    private AgentToolInvocationMapper invocationMapper;

    private AgentEffectService effectService;

    /** 漂移告警是本类唯一要断"有没有出声"的地方，所以要能截获日志。 */
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(AgentEffectService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** MyBatis-Plus 要先知道实体元信息，否则构造 wrapper 时解析不出列名。 */
    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentEffect.class);
        // 漂移判定要按 effect_state 过滤 invocation，所以这张表也要注册。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentToolInvocation.class);
    }

    @BeforeEach
    void setUp() {
        effectService = new AgentEffectService(effectMapper, invocationMapper);
        appender.start();
        serviceLogger.addAppender(appender);
        // 铸造默认成功。绝大多数用例关心的是"采纳还是铸造"以及查询条件，不关心 insert 的返回值；
        // 而 Mockito 对 int 的默认返回是 0，会让铸造抛"副作用身份创建失败"——那个失败会盖住
        // 用例真正要断的东西。要断"落库失败"的用例自己覆盖这个桩即可。
        lenient().when(effectMapper.insert(any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException("测试里的 JSON 字面量不合法：" + text, exception);
        }
    }

    private static AgentRun run(String intentEpoch) {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setUserId(USER_ID);
        run.setIntentEpoch(intentEpoch);
        return run;
    }

    private static AgentEffect openEffect(long id, String effectId, int createdStepNo) {
        AgentEffect effect = new AgentEffect();
        effect.setId(id);
        effect.setEffectId(effectId);
        effect.setStatus(AgentEffect.STATUS_OPEN);
        effect.setCreatedStepNo(createdStepNo);
        effect.setToolName(TOOL);
        effect.setIntentEpoch(INTENT_EPOCH);
        return effect;
    }

    /** 带参数原文的既有副作用——漂移告警要把它的 {@code args_json} 一起打出来供人工比对。 */
    private static AgentEffect openEffectWithArgs(long id, String effectId, int createdStepNo,
                                                  String argsJson) {
        AgentEffect effect = openEffect(id, effectId, createdStepNo);
        effect.setArgsJson(argsJson);
        return effect;
    }

    private static AgentToolInvocation invocation(String effectId, String effectState) {
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setEffectId(effectId);
        invocation.setEffectState(effectState);
        return invocation;
    }

    /**
     * 捕获服务发给数据库的查询条件。
     *
     * <p><b>返回前必须先渲染一次 SQL。</b>MyBatis-Plus 的 {@code paramNameValuePairs} 是**惰性**
     * 填充的：条件里的值要等 SQL 被真正渲染时才写进去，所以直接读它只会得到一个空 Map。
     * 这个坑很隐蔽——断言不会报"读不到参数"，只会报"值里不包含 X"，看起来像被测代码漏加了条件，
     * 于是会去改本来正确的实现。统一在这里渲染一次，免得每个用例各记一遍。</p>
     */
    private LambdaQueryWrapper<AgentEffect> capturedAdoptionQuery() {
        return capturedEffectQueries().get(0);
    }

    /**
     * 按发生顺序捕获本类发给 {@code agent_effect} 的每一次 {@code selectList}。
     *
     * <p><b>不能再写 {@code verify(...)}（恰好一次）。</b>B7 给铸造路径加了一次漂移探测查询，
     * 于是"走到铸造"的用例会有两次 {@code selectList}：第 0 次是采纳查询，第 1 次是漂移探测。
     * 顺序是固定的——采纳先问、问不到才探测漂移——所以按下标取是可靠的。</p>
     */
    private List<LambdaQueryWrapper<AgentEffect>> capturedEffectQueries() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AgentEffect>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(effectMapper, atLeastOnce()).selectList(captor.capture());
        List<LambdaQueryWrapper<AgentEffect>> queries = captor.getAllValues();
        for (LambdaQueryWrapper<AgentEffect> query : queries) {
            query.getSqlSegment();
        }
        return queries;
    }

    /** 漂移探测的那次查询；只有走到了铸造才会有它。 */
    private LambdaQueryWrapper<AgentEffect> capturedDriftQuery() {
        List<LambdaQueryWrapper<AgentEffect>> queries = capturedEffectQueries();
        assertThat(queries).hasSize(2);
        return queries.get(1);
    }

    /** 漂移判定对 invocation 的查询条件。 */
    private LambdaQueryWrapper<AgentToolInvocation> capturedDriftInvocationQuery() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AgentToolInvocation>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invocationMapper).selectList(captor.capture());
        LambdaQueryWrapper<AgentToolInvocation> query = captor.getValue();
        query.getSqlSegment();
        return query;
    }

    /** 服务真正吐出来的漂移告警。 */
    private List<String> driftWarnings() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("EFFECT_ARG_DRIFT"))
                .toList();
    }

    /** 同上：更新条件里的值同样是惰性填充的。 */
    private LambdaUpdateWrapper<AgentEffect> capturedTransitionUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AgentEffect>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(effectMapper).update(any(), captor.capture());
        LambdaUpdateWrapper<AgentEffect> update = captor.getValue();
        update.getSqlSet();
        update.getSqlSegment();
        return update;
    }

    private AgentEffect capturedInsert() {
        ArgumentCaptor<AgentEffect> captor = ArgumentCaptor.forClass(AgentEffect.class);
        verify(effectMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------- 采纳 ----------

    @Test
    void adoptsAnOpenEffectWithTheSameEpochToolAndFingerprintFromAnEarlierStep() {
        JsonNode args = json("{\"scheduledAt\":\"2026-09-26T09:00\"}");
        when(effectMapper.selectList(any())).thenReturn(List.of(openEffect(11L, "effect-a", 2)));

        String resolved = effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH),
                STEP_NO, TOOL, args);

        assertThat(resolved).isEqualTo("effect-a");
        verify(effectMapper, never()).insert(any());
    }

    @Test
    void theAdoptionQueryIsScopedToTheEpochToolFingerprintAndOpenStatus() {
        JsonNode args = json("{\"scheduledAt\":\"2026-09-26T09:00\"}");
        when(effectMapper.selectList(any())).thenReturn(List.of());

        effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH), STEP_NO, TOOL, args);

        LambdaQueryWrapper<AgentEffect> query = capturedAdoptionQuery();
        String where = query.getSqlSegment();

        // 四条判据加状态限制，一条都不能少：少了 epoch 会把两件不同的事合并成一件；
        // 少了 status 会让已完成的动作被"重试"复用，于是用户真的要做的第二次被吞掉。
        assertThat(where).contains("intent_epoch").contains("tool_name")
                .contains("args_fingerprint").contains("status").contains("created_step_no");
        assertThat(query.getParamNameValuePairs().values())
                .contains(INTENT_EPOCH, TOOL, AgentEffect.STATUS_OPEN, STEP_NO);
    }

    @Test
    void adoptionRequiresAStrictlyEarlierStepSoTwoCallsInOneBatchStayTwoActions() {
        // 同一批次里的两次相同调用是**两个有意的动作**（用户要发两条提醒）。少了这条，
        // 第二条会采纳第一条，于是"两条提醒"变成一条。
        JsonNode args = json("{\"title\":\"喝水\"}");
        when(effectMapper.selectList(any())).thenReturn(List.of());

        effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH), STEP_NO, TOOL, args);

        assertThat(capturedAdoptionQuery().getSqlSegment()).contains("created_step_no <");
    }

    @Test
    void theAdoptionQueryIsScopedToTheRunsOwnEpoch() {
        // 跨周期一律不采纳：用户新说一句话就开新周期，那时同样的调用是一件新事。
        JsonNode args = json("{}");
        when(effectMapper.selectList(any())).thenReturn(List.of());

        effectService.resolveEffect(USER_ID, CONVERSATION_ID, run("another-epoch"), STEP_NO, TOOL, args);

        assertThat(capturedAdoptionQuery().getParamNameValuePairs().values())
                .contains("another-epoch")
                .doesNotContain(INTENT_EPOCH);
    }

    @Test
    void takesTheEarliestCandidateWhenAdoptionIsAmbiguousInsteadOfMinting() {
        // 与计划的一处刻意偏离：计划要求"多于一个就铸新"。铸新会**级联**——每次重试都因为
        // "不止一个"再铸一个，OPEN 候选越积越多，每次重试都拿到新键，重试越多重复写风险越大。
        // 取最早则稳定：所有重试都落到同一个 effectId 上。
        when(effectMapper.selectList(any()))
                .thenReturn(List.of(openEffect(11L, "effect-earliest", 1),
                        openEffect(12L, "effect-later", 2)));

        String resolved = effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH),
                STEP_NO, TOOL, json("{}"));

        assertThat(resolved).isEqualTo("effect-earliest");
        verify(effectMapper, never()).insert(any());
    }

    // ---------- 铸造 ----------

    @Test
    void mintsANewEffectWhenNothingIsAdoptable() {
        JsonNode args = json("{\"scheduledAt\":\"2026-09-26T09:00\"}");
        when(effectMapper.selectList(any())).thenReturn(List.of());
        when(effectMapper.insert(any())).thenReturn(1);

        String resolved = effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH),
                STEP_NO, TOOL, args);

        AgentEffect minted = capturedInsert();
        assertThat(resolved).isEqualTo(minted.getEffectId()).isNotBlank();
        assertThat(minted.getUserId()).isEqualTo(USER_ID);
        assertThat(minted.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(minted.getRunId()).isEqualTo(RUN_ID);
        assertThat(minted.getIntentEpoch()).isEqualTo(INTENT_EPOCH);
        assertThat(minted.getToolName()).isEqualTo(TOOL);
        assertThat(minted.getCreatedStepNo()).isEqualTo(STEP_NO);
        assertThat(minted.getStatus()).isEqualTo(AgentEffect.STATUS_OPEN);
        assertThat(minted.getSettledAt()).isNull();
        assertThat(minted.getArgsFingerprint())
                .isEqualTo(AgentArgsFingerprint.of(TOOL, args))
                .hasSize(64);
        assertThat(minted.getArgsJson()).contains("2026-09-26T09:00");
    }

    @Test
    void mintsAnEffectWhoseFingerprintMatchesTheOneTheQuerySearchedWith() {
        // 查询用的指纹与落库的指纹必须是同一个函数算的。分叉的话，新铸的 Effect 永远
        // 匹配不上后续重试的查询——采纳规则静默失效，而这不会有任何报错。
        JsonNode args = json("{\"b\":2,\"a\":1}");
        when(effectMapper.selectList(any())).thenReturn(List.of());
        when(effectMapper.insert(any())).thenReturn(1);

        effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH), STEP_NO, TOOL, args);

        assertThat(capturedAdoptionQuery().getParamNameValuePairs().values())
                .contains(AgentArgsFingerprint.of(TOOL, args));
        assertThat(capturedInsert().getArgsFingerprint())
                .isEqualTo(AgentArgsFingerprint.of(TOOL, args));
    }

    @Test
    void rejectsAMintThatTheDatabaseDidNotPersist() {
        when(effectMapper.selectList(any())).thenReturn(List.of());
        when(effectMapper.insert(any())).thenReturn(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> effectService.resolveEffect(
                        USER_ID, CONVERSATION_ID, run(INTENT_EPOCH), STEP_NO, TOOL, json("{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("副作用身份");
    }

    // ---------- 漂移告警（B7-5） ----------

    /**
     * 铸造路径上两次查询的桩。
     *
     * <p>必须按调用顺序给两个不同的返回值，不能只写一个：采纳查询带的是"**等于**新指纹"，
     * 漂移探测带的是"**不等于**新指纹"。只写一个的话两次拿到同一批候选，于是采纳会成功、
     * 根本走不到漂移分支——用例会"通过"，但什么都没验证。第一次给空（采纳查不到），
     * 第二次给候选（漂移查得到），这才对应真实库里会发生的事。</p>
     */
    private void stubAdoptionMissesButDriftFinds(AgentEffect... drifted) {
        when(effectMapper.selectList(any())).thenReturn(List.of(), List.of(drifted));
    }

    @Test
    void aChangedRetryThatWasAlreadyDispatchedRaisesTheDriftSignal() {
        // §7 风险表里"Effect 采纳的残余漂移"那条：模型重试时改了参数，指纹对不上，于是铸新
        // effectId、下游幂等键跟着换——而前一次可能已经写出去了。机制只能保守地铸新（不能靠
        // 猜去采纳），所以**告警是唯一的缓解手段**。这是 B6 留下的真实未完成项。
        stubAdoptionMissesButDriftFinds(openEffectWithArgs(11L, "effect-09", 2,
                "{\"scheduledAt\":\"2026-09-26T09:00\"}"));
        when(invocationMapper.selectList(any())).thenReturn(
                List.of(invocation("effect-09", AgentToolInvocation.EFFECT_STATE_DISPATCHED)));

        effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH), STEP_NO, TOOL,
                json("{\"scheduledAt\":\"2026-09-26T15:00\"}"));

        assertThat(driftWarnings()).hasSize(1);
        assertThat(driftWarnings().get(0))
                .contains("effect-09")
                .contains("2026-09-26T09:00")   // 上一次的参数原文，供人工比对差异
                .contains("2026-09-26T15:00");  // 这一次的参数
    }

    @Test
    void theDriftProbeOnlyAsksForEffectsThatAlreadyCrossedTheBoundary() {
        // 只在"确实发出去过"时才告警：PREPARED 的远端什么都没发生，改参数只是"第一次真正要做的
        // 动作换了个样子"，不构成重复写。每条都告警会把真正危险的那几条淹掉。
        // 过滤发生在 SQL 上，所以这里断的是查询条件本身。
        stubAdoptionMissesButDriftFinds(openEffectWithArgs(11L, "effect-09", 2, "{\"a\":1}"));
        when(invocationMapper.selectList(any())).thenReturn(List.of());

        effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH), STEP_NO, TOOL, json("{\"a\":2}"));

        assertThat(driftWarnings()).isEmpty();
        LambdaQueryWrapper<AgentToolInvocation> probe = capturedDriftInvocationQuery();
        assertThat(probe.getSqlSegment()).contains("effect_state").contains("effect_id");
        assertThat(probe.getParamNameValuePairs().values())
                .contains("effect-09")
                .contains(AgentToolInvocation.EFFECT_STATE_DISPATCHED,
                        AgentToolInvocation.EFFECT_STATE_CONFIRMED)
                // 关键：PREPARED 不在名单里，所以"还没发出去"的候选永远不会被判成漂移。
                .doesNotContain(AgentToolInvocation.EFFECT_STATE_PREPARED);
    }

    @Test
    void theDriftProbeLooksForOpenEffectsOfTheSameToolWithADifferentFingerprintFromAnEarlierStep() {
        JsonNode args = json("{\"a\":2}");
        when(effectMapper.selectList(any())).thenReturn(List.of());

        effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH), STEP_NO, TOOL, args);

        LambdaQueryWrapper<AgentEffect> drift = capturedDriftQuery();
        String where = drift.getSqlSegment();
        // 与采纳查询同源的四条范围，但指纹方向相反：这里找的是"同一件事、参数却变了"。
        assertThat(where).contains("intent_epoch").contains("tool_name").contains("status")
                .contains("args_fingerprint").contains("created_step_no");
        assertThat(where).contains("<>");
        assertThat(drift.getParamNameValuePairs().values())
                .contains(INTENT_EPOCH, TOOL, AgentEffect.STATUS_OPEN, STEP_NO)
                .contains(AgentArgsFingerprint.of(TOOL, args));
    }

    @Test
    void anIdenticalRetryNeverProbesForDrift() {
        // 采纳成功就说明"还是那件事"，根本没有漂移可言——不该为此多查一次库。
        when(effectMapper.selectList(any())).thenReturn(List.of(openEffect(11L, "effect-a", 2)));

        String resolved = effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH),
                STEP_NO, TOOL, json("{}"));

        assertThat(resolved).isEqualTo("effect-a");
        assertThat(capturedEffectQueries()).hasSize(1);
        verify(invocationMapper, never()).selectList(any());
        assertThat(driftWarnings()).isEmpty();
    }

    @Test
    void onlyTheCandidatesThatWereActuallyDispatchedAreReported() {
        // 漂移候选可能有几条，但只有越过边界的才可能已经在远端生效。报多了等于没报。
        stubAdoptionMissesButDriftFinds(
                openEffectWithArgs(11L, "effect-never-sent", 1, "{\"a\":1}"),
                openEffectWithArgs(12L, "effect-sent", 2, "{\"a\":9}"));
        when(invocationMapper.selectList(any())).thenReturn(
                List.of(invocation("effect-sent", AgentToolInvocation.EFFECT_STATE_CONFIRMED)));

        effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH), STEP_NO, TOOL, json("{\"a\":2}"));

        assertThat(driftWarnings()).hasSize(1);
        assertThat(driftWarnings().get(0)).contains("effect-sent").doesNotContain("effect-never-sent");
    }

    @Test
    void aDriftedRetryStillMintsItsOwnEffectInsteadOfAdopting() {
        // 告警**不能改变行为**。改参数就是内容不同，按判据该铸新——靠"参数像"去采纳正是
        // 被否决的那条路（会让"同义改写"和"合法改内容"两种情况都判错）。
        stubAdoptionMissesButDriftFinds(openEffectWithArgs(11L, "effect-09", 2, "{\"a\":1}"));
        when(invocationMapper.selectList(any())).thenReturn(
                List.of(invocation("effect-09", AgentToolInvocation.EFFECT_STATE_DISPATCHED)));

        String resolved = effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(INTENT_EPOCH),
                STEP_NO, TOOL, json("{\"a\":2}"));

        assertThat(resolved).isNotEqualTo("effect-09");
        assertThat(capturedInsert().getEffectId()).isEqualTo(resolved);
        assertThat(driftWarnings()).hasSize(1);
    }

    @Test
    void aDriftedRetryWithoutAnIntentEpochIsNotEvenProbed() {
        // 没有周期标识时连 Effect 都不建，自然谈不上"同一件事的漂移"。这条防止有人把探测
        // 挪到 early return 之前——那会让迁移前的 Run 每次写调用都多两次查询。
        assertThat(effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(null),
                STEP_NO, TOOL, json("{\"a\":2}"))).isNull();

        verify(effectMapper, never()).selectList(any());
        verify(invocationMapper, never()).selectList(any());
        assertThat(driftWarnings()).isEmpty();
    }

    // ---------- 迁移兼容 ----------

    @Test
    void aRunWithoutAnIntentEpochGetsNoEffectAtAll() {
        // 迁移前创建的 Run 没有周期标识，而周期是采纳的判据——凭猜测编一个会把"这次重试属于哪次诉求"
        // 写错。宁可这次调用退回"每次一个新键"（B6 之前的行为），也不要把两件不同的事合并。
        assertThat(effectService.resolveEffect(USER_ID, CONVERSATION_ID, run(null),
                STEP_NO, TOOL, json("{}"))).isNull();

        verify(effectMapper, never()).selectList(any());
        verify(effectMapper, never()).insert(any());
    }

    @Test
    void aBlankIntentEpochIsTreatedAsAbsent() {
        assertThat(effectService.resolveEffect(USER_ID, CONVERSATION_ID, run("   "),
                STEP_NO, TOOL, json("{}"))).isNull();

        verify(effectMapper, never()).insert(any());
    }

    @Test
    void aRunThatIsNotPersistedYetGetsNoEffect() {
        // 没有 id 就没法把 Effect 归属到某个 Run 上，而归属是后续对账的线索。
        AgentRun unsaved = run(INTENT_EPOCH);
        unsaved.setId(null);

        assertThat(effectService.resolveEffect(USER_ID, CONVERSATION_ID, unsaved,
                STEP_NO, TOOL, json("{}"))).isNull();

        verify(effectMapper, never()).insert(any());
    }

    @Test
    void aMissingRunIsTolerated() {
        assertThat(effectService.resolveEffect(USER_ID, CONVERSATION_ID, null,
                STEP_NO, TOOL, json("{}"))).isNull();

        verify(effectMapper, never()).selectList(any());
    }

    // ---------- 结算与放弃 ----------

    @Test
    void settlingOnlyTransitionsFromOpen() {
        when(effectMapper.update(any(), any())).thenReturn(1);

        effectService.settleEffect("effect-a");

        LambdaUpdateWrapper<AgentEffect> update = capturedTransitionUpdate();

        // 守卫在 WHERE 上：条件更新限定在 OPEN，所以它幂等，也不可能把已结算的拉回可采纳。
        assertThat(update.getSqlSegment()).contains("effect_id").contains("status");
        assertThat(update.getParamNameValuePairs().values())
                .contains("effect-a", AgentEffect.STATUS_OPEN, AgentEffect.STATUS_SETTLED);
        assertThat(update.getSqlSet()).contains("status").contains("settled_at");
    }

    @Test
    void abandoningOnlyTransitionsFromOpen() {
        when(effectMapper.update(any(), any())).thenReturn(1);

        effectService.abandonEffect("effect-a");

        LambdaUpdateWrapper<AgentEffect> update = capturedTransitionUpdate();

        assertThat(update.getSqlSegment()).contains("effect_id").contains("status");
        assertThat(update.getParamNameValuePairs().values())
                .contains("effect-a", AgentEffect.STATUS_OPEN, AgentEffect.STATUS_ABANDONED);
        assertThat(update.getSqlSet()).contains("status").contains("settled_at");
    }

    @Test
    void losingTheTransitionRaceIsNotAnError() {
        // 另一个调用先一步结算了它——副作用的状态只前进不后退，不该覆盖也不该报错。
        when(effectMapper.update(any(), any())).thenReturn(0);

        effectService.settleEffect("effect-a");
        effectService.abandonEffect("effect-a");

        verify(effectMapper, org.mockito.Mockito.times(2)).update(any(), any());
    }

    @Test
    void aBlankEffectIdNeverTouchesTheDatabase() {
        effectService.settleEffect(null);
        effectService.settleEffect("");
        effectService.abandonEffect("   ");

        verify(effectMapper, never()).update(any(), any());
    }

    @Test
    void findByEffectIdIgnoresBlankIds() {
        assertThat(effectService.findByEffectId(null)).isEmpty();
        assertThat(effectService.findByEffectId("")).isEmpty();

        verify(effectMapper, never()).selectOne(any());
    }

    @Test
    void findByEffectIdReturnsThePersistedEffect() {
        AgentEffect effect = openEffect(11L, "effect-a", 1);
        when(effectMapper.selectOne(any())).thenReturn(effect);

        assertThat(effectService.findByEffectId("effect-a")).contains(effect);
    }

    @Test
    void anEffectIsOnlyAdoptableWhileOpen() {
        // 这条把"状态语义"本身钉住，免得以后有人放宽 isAdoptable() 而没意识到后果。
        assertThat(openEffect(1L, "a", 1).isAdoptable()).isTrue();

        AgentEffect settled = openEffect(1L, "a", 1);
        settled.setStatus(AgentEffect.STATUS_SETTLED);
        assertThat(settled.isAdoptable()).isFalse();

        AgentEffect abandoned = openEffect(1L, "a", 1);
        abandoned.setStatus(AgentEffect.STATUS_ABANDONED);
        assertThat(abandoned.isAdoptable()).isFalse();
    }
}
