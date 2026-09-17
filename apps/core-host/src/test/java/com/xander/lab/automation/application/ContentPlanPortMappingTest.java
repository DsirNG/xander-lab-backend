package com.xander.lab.automation.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import com.xander.lab.automation.infrastructure.BlogPlanRunMapper;
import com.xander.lab.automation.spi.ContentPlanView;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.integration.mcp.csdn.application.CsdnCredentialService;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 钉住 {@code BlogContentPlanVO → ContentPlanView} 的<b>逐字段落点</b>。
 *
 * <p>理由与 {@code EmailReminderPortMappingTest} 相同：契约模块看不见域类型，
 * 真正会静默出错的是换算那一行。{@link ContentPlanView} 里有三个相邻的
 * {@code Boolean}（{@code syncCsdn} / {@code syncJuejin} / {@code runOnce}）
 * 和两个相邻的 {@code Instant}（{@code nextRunAt} / {@code lastRunAt}），
 * 换位不会有任何编译错误。</p>
 *
 * <p>⚠️ 三个相邻的 {@code Boolean} 只有两个取值，<b>一组</b>字面量钉不满：
 * 取 {@code (true, false, true)} 时把第一个和第三个对调测试仍然全绿。
 * 所以下面用<b>两组互补</b>的字面量 —— 两组都过，任意两个分量对调必被抓住。
 * 这条纪律来自 {@code payment-api} 那次 {@code TokenUsage} 分量顺序事故。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentPlanPortMappingTest {

    private static final Instant NEXT_RUN_AT = Instant.parse("2026-08-17T01:00:00Z");
    private static final Instant LAST_RUN_AT = Instant.parse("2026-08-16T03:20:00Z");

    @Mock private BlogContentPlanMapper planMapper;
    @Mock private BlogPlanRunMapper runMapper;
    @Mock private BlogPlanRunService runService;
    @Mock private CsdnCredentialService csdnCredentialService;
    @Mock private JuejinCredentialService juejinCredentialService;
    @Mock private BlogAgentService agentService;
    @Mock private RecitationMaterialPort materialPort;

    private BlogContentPlanService newService() {
        return new BlogContentPlanService(planMapper, runMapper, runService,
                csdnCredentialService, juejinCredentialService, agentService, materialPort);
    }

    @Test
    void everyViewComponentComesFromItsOwnSourceField() {
        when(planMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(plan(true, false, true)));

        ContentPlanView view = newService().listAll(7L, "").get(0);

        assertThat(view.id()).isEqualTo(41L);
        assertThat(view.topic()).isEqualTo("主题字段");
        assertThat(view.status()).isEqualTo("PAUSED");
        assertThat(view.timezone()).isEqualTo("Europe/Paris");
        assertThat(view.triggerTimes()).containsExactly("07:45", "21:15");
        assertThat(view.syncCsdn()).isTrue();
        assertThat(view.syncJuejin()).isFalse();
        assertThat(view.runOnce()).isTrue();
        assertThat(view.nextRunAt()).isEqualTo(NEXT_RUN_AT);
        assertThat(view.lastRunAt()).isEqualTo(LAST_RUN_AT);
        assertThat(view.errorMessage()).isEqualTo("错误字段");
    }

    /**
     * 第二组互补字面量：三个布尔全反过来。单独看它同样钉不满三个分量，
     * 但与上一条<b>合起来</b>，{@code (syncCsdn, syncJuejin, runOnce)} 的
     * 任意两个对调都会让其中一条红。
     */
    @Test
    void booleanComponentsArePinnedByASecondComplementaryCombination() {
        when(planMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(plan(false, true, false)));

        ContentPlanView view = newService().listAll(7L, "").get(0);

        assertThat(view.syncCsdn()).isFalse();
        assertThat(view.syncJuejin()).isTrue();
        assertThat(view.runOnce()).isFalse();
    }

    /** {@code triggerTimes} 走的是 {@code allTriggerTimes()}（带 legacy 单时间兜底）。 */
    @Test
    void legacySingleTriggerTimeStillShowsUpAsAList() {
        BlogContentPlan plan = plan(false, false, false);
        plan.setTriggerTimesJson(null);
        plan.setTriggerTime("06:30");
        when(planMapper.selectList(any(Wrapper.class))).thenReturn(List.of(plan));

        ContentPlanView view = newService().listAll(7L, "").get(0);

        assertThat(view.triggerTimes()).containsExactly("06:30");
    }

    private static BlogContentPlan plan(Boolean syncCsdn, Boolean syncJuejin, Boolean runOnce) {
        BlogContentPlan plan = new BlogContentPlan();
        plan.setId(41L);
        plan.setUserId(7L);
        plan.setTopic("主题字段");
        plan.setStatus("PAUSED");
        plan.setTimezone("Europe/Paris");
        plan.setTriggerTimesJson(BlogContentPlan.toJson(List.of("07:45", "21:15")));
        plan.setSyncCsdn(syncCsdn);
        plan.setSyncJuejin(syncJuejin);
        plan.setRunOnce(runOnce);
        plan.setNextRunAt(NEXT_RUN_AT);
        plan.setLastRunAt(LAST_RUN_AT);
        plan.setErrorMessage("错误字段");
        return plan;
    }
}
