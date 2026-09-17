package com.xander.lab.automation.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xander.lab.automation.domain.EmailReminderTask;
import com.xander.lab.automation.infrastructure.EmailReminderTaskMapper;
import com.xander.lab.automation.spi.EmailReminderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 钉住 {@code EmailReminderTaskVO → EmailReminderView} 的<b>逐字段落点</b>。
 *
 * <p>为什么必须在应用模块而不是契约模块测：契约模块看不见
 * {@code EmailReminderTaskVO} 与 {@code EmailReminderTask}，
 * 它的 {@code RecordShapeTest} 只能证明"契约自己没变"。真正会静默出错的是
 * <b>换算那一行</b> —— {@link EmailReminderView} 里有六个相邻的 {@code String}
 * 和两个 {@code Instant}，把 {@code recipientEmail} 填成 {@code subject}
 * 不会有任何编译错误，只会把收件人当主题发出去。</p>
 *
 * <p>⚠️ 所以这里给<b>每个分量一个互不相同的字面量</b>。只写"看起来对"的值
 * （比如所有 String 都用 {@code "x"}）的话，把两个分量对调测试仍然全绿 ——
 * 这条纪律是从 {@code payment-api} 那次 {@code TokenUsage} 分量顺序事故来的。</p>
 */
@ExtendWith(MockitoExtension.class)
class EmailReminderPortMappingTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2026-08-17T01:00:00Z");
    private static final Instant SENT_AT = Instant.parse("2026-08-17T02:30:00Z");

    @Mock
    private EmailReminderTaskMapper taskMapper;

    private EmailReminderService service;

    @BeforeEach
    void setUp() {
        service = new EmailReminderService(taskMapper);
        ReflectionTestUtils.setField(service, "senderEmail", "noreply@example.com");
    }

    @Test
    void everyViewComponentComesFromItsOwnSourceField() {
        EmailReminderTask task = new EmailReminderTask();
        task.setId(41L);
        task.setUserId(7L);
        task.setRecipientEmail("recipient@example.com");
        task.setSubject("主题字段");
        task.setMessage("正文字段");
        task.setFrequency("WEEKLY");
        task.setTimezone("Europe/Paris");
        task.setSendTime("07:45");
        task.setScheduledAt(SCHEDULED_AT);
        task.setStatus("SENT");
        task.setErrorMessage("错误字段");
        task.setSentAt(SENT_AT);
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(task));

        List<EmailReminderView> views = service.listAll(7L, "", "");

        assertThat(views).hasSize(1);
        EmailReminderView view = views.get(0);
        assertThat(view.id()).isEqualTo(41L);
        assertThat(view.recipientEmail()).isEqualTo("recipient@example.com");
        assertThat(view.subject()).isEqualTo("主题字段");
        assertThat(view.message()).isEqualTo("正文字段");
        assertThat(view.frequency()).isEqualTo("WEEKLY");
        assertThat(view.timezone()).isEqualTo("Europe/Paris");
        assertThat(view.sendTime()).isEqualTo("07:45");
        assertThat(view.scheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(view.status()).isEqualTo("SENT");
        assertThat(view.errorMessage()).isEqualTo("错误字段");
        assertThat(view.sentAt()).isEqualTo(SENT_AT);
    }

    /**
     * {@code frequency} 与 {@code timezone} 的兜底住在 {@code toVO} 里，
     * 换算层不得再兜第二次 —— 兜两次就是两份规则。这条测试同时钉住
     * "兜底确实生效"和"兜底只有一处"：如果哪天有人把兜底搬到 {@code toView}，
     * 把 {@code toVO} 里的删掉，这里仍然绿，但 {@code list}（前端路径）会开始返回 null。
     * 所以 {@code EmailReminderServiceTest} 里还有一条针对 {@code list} 的对应断言。
     */
    @Test
    void nullFrequencyAndTimezoneFallBackBeforeNarrowing() {
        EmailReminderTask task = new EmailReminderTask();
        task.setId(42L);
        task.setUserId(7L);
        task.setFrequency(null);
        task.setTimezone(null);
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(task));

        EmailReminderView view = service.listAll(7L, "", "").get(0);

        assertThat(view.frequency()).isEqualTo("ONCE");
        assertThat(view.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(view.sendTime()).isNull();
        assertThat(view.scheduledAt()).isNull();
        assertThat(view.sentAt()).isNull();
        assertThat(view.errorMessage()).isNull();
    }
}
