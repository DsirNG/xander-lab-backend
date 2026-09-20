package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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

    /**
     * {@code broadcast} 的返回值是"当前注册的连接数"，一个用户的多个标签页各算一个。
     *
     * <p>这条路径在第二十刀之前<b>一条测试都没有</b>：{@code broadcast} 只被 admin 的
     * 诊断控制器调用，而 {@code BlogPlanRunServiceTest} 把整个 SSE 服务 mock 掉了。
     * 它也是那个端点唯一真正读的值 —— 管理台要的就是"刚才那条广播有没有人收到"。</p>
     */
    @Test
    void broadcastReturnsTheNumberOfCurrentlyRegisteredConnections() {
        assertThat(service.broadcast("SSE_TEST_BROADCAST", "t", "m"))
                .as("没有任何连接时应当返回 0，而不是抛异常")
                .isZero();

        service.subscribe(7L);
        service.subscribe(7L);
        service.subscribe(8L);

        assertThat(service.broadcast("SSE_TEST_BROADCAST", "t", "m"))
                .as("两个标签页 + 一个标签页 = 3 个连接；返回的是连接数，不是用户数")
                .isEqualTo(3);
    }

    /**
     * ⭐ 收窄签名是<b>行为保持</b>的：{@code broadcast} 扇出给 {@code push} 时
     * {@code planId} / {@code runId} 为 {@code null}。
     *
     * <p>第二十刀把 {@code broadcast} 从五参收窄到三参（那两个 {@code Long} 是 blog-plan 域的
     * 概念，唯一调用方永远传 {@code null}）。收窄之后"那两个值仍然以 null 转发"这件事必须
     * 被钉住，否则线上载荷会悄悄变：{@code push} 把 {@code null} 序列化成空串，
     * 一旦这里改成别的值，前端拿到的 {@code planId} / {@code runId} 就从 {@code ""}
     * 变成有内容了 —— 而那正是"纯重构"这句话要证明的东西。</p>
     */
    @Test
    void broadcastFansOutToPushWithoutPlanOrRunContext() {
        BlogPlanSseService spied = spy(new BlogPlanSseService(new ObjectMapper()));
        spied.subscribe(7L);

        spied.broadcast("SSE_TEST_BROADCAST", "标题", "正文");

        verify(spied).push(7L, "SSE_TEST_BROADCAST", "标题", "正文", null, null);
    }
}
