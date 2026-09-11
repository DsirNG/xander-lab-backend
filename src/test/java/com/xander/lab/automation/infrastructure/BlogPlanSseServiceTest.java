package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The notification stream is an in-memory convenience over the durable
 * notification table: offline users and closed connectors must never throw. */
class BlogPlanSseServiceTest {

    private final BlogPlanSseService service = new BlogPlanSseService(new ObjectMapper());

    @Test
    void pushToUserWithNoSubscriptionIsHarmless() {
        assertThatCode(() -> service.push(999L, "REVIEW_REJECTED", "t", "m", 1L, 2L))
                .doesNotThrowAnyException();
    }

    @Test
    void subscribeReturnsEmitterAndPushAfterCompletionNeverThrows() {
        SseEmitter emitter = service.subscribe(7L);
        assertThat(emitter).isNotNull();
        emitter.complete();
        assertThatCode(() -> service.push(7L, "RUN_FAILED", "t", "m", 1L, 2L))
                .doesNotThrowAnyException();
    }

    /**
     * 同一个 emitter 会被重复通知（三个生命周期回调 + 心跳线程），注销必须幂等。
     *
     * <p>注意这里断言的是"不抛异常、且重复注销不会连带摘掉别的连接"，
     * 而不是"立刻不再计入广播"——emitter 的注销由它自己的生命周期回调驱动，
     * 存在调度延迟，拿广播数当断言测的是时序而不是幂等性。</p>
     */
    @Test
    void removingTheSameEmitterRepeatedlyIsIdempotentAndKeepsOtherTabs() {
        SseEmitter first = service.subscribe(7L);
        SseEmitter second = service.subscribe(7L);

        first.complete();
        first.complete();
        first.complete();

        // 重复注销之后，另一个 tab 仍然可以正常推送（没被连带摘掉）。
        assertThatCode(() -> service.push(7L, "RUN_FAILED", "t", "m", 1L, 2L))
                .doesNotThrowAnyException();
        assertThatCode(second::complete).doesNotThrowAnyException();
    }
}
