package com.xander.lab.automation.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link NotificationBroadcastPort} 的形状。
 *
 * <p>本测试多钉一件本模块另外三个端口测试钉不了的事：<b>签名里真的没有框架类型</b>。
 * 这条边（{@code admin → automation}）此前之所以被记成"卡在别的决定上"，
 * 是因为 {@code BlogPlanSseService} 的 {@code subscribe} 返回 {@code SseEmitter}
 * —— 那句话对<b>整个类</b>是对的，对<b>这条边</b>是错的。边只经过 {@code broadcast}，
 * 而它收三个 {@code String}、返回 {@code int}。所以这里专门把"没有框架类型"钉成一条断言：
 * 以后谁想"顺手"把 {@code subscribe} 也搬进来，这条会红。</p>
 */
class NotificationBroadcastPortTest {

    @Test
    void declaresExactlyTheOneKnownMethodWithThisExactSignature() {
        assertEquals(List.of(
                "broadcast(String, String, String) -> int"
        ), ContractShape.declaredSignatures(NotificationBroadcastPort.class),
                "端口方法集变了。唯一消费方 admin 只做一件事：向在线连接广播一条瞬时消息。"
                        + "subscribe / push / 连接注册表 / 封禁校验都留在实现里 —— "
                        + "subscribe 返回 SseEmitter（框架类型），push 收 blog-plan 域的 planId / runId，"
                        + "两者都不该出现在契约上。");
    }

    @Test
    void returnsIntSoTheProviderSideIsAPureTypeReplacement() {
        // 与实现同形：BlogPlanSseService#broadcast 逐字相同 ⇒ 提供方只需
        // 加 implements + @Override。若改成 void，Java 不允许同名同参只差返回值，
        // 实现侧就得改名 —— 那是与这条缝无关的改动。
        //
        // ⚠️ 按名字取，不按下标：getDeclaredMethods() 的顺序 JVM 并不保证。
        assertEquals(int.class, methodNamed("broadcast").getReturnType(),
                "返回值必须是原语 int（接收连接数）。改成 void 会让实现侧必须改名；"
                        + "改成装箱 Integer 会多出一个未计数状态，而这里只可能是 0 或正数。");
    }

    @Test
    void takesExactlyThreeStringsAndNoPlanOrRunContext() {
        Method broadcast = methodNamed("broadcast");
        assertEquals(3, broadcast.getParameterCount(),
                "参数个数变了。三个 String（type / title / message）就是广播一句话所需的全部。"
                        + "⚠️ 这里刻意与实现的旧五参签名不同形 —— 见接口注释："
                        + "那边富余的是消费方忽略的返回值，这边富余的会是消费方必须写死的 null。");
        assertTrue(Arrays.stream(broadcast.getParameterTypes()).allMatch(type -> type == String.class),
                "三个参数都必须是 String。出现别的类型通常意味着把实现细节搬进了契约。");
        // 反面守卫：planId / runId 是 blog-plan 域的概念，唯一调用方（admin 的诊断端点）
        // 永远传 null。一个要求调用方每次都撒谎的参数不该回到端口上。
        for (var parameter : broadcast.getParameters()) {
            assertFalse(parameter.getName().equals("planId") || parameter.getName().equals("runId"),
                    "端口上又出现了 " + parameter.getName() + "：它是 blog-plan 域的概念，"
                            + "而 admin 的诊断广播没有这个上下文。被迫传一个自己永远不用的参数"
                            + "正是 §16.2 的反面判据。要真的支持带上下文的广播，"
                            + "先确认有第二个消费方需要它。");
        }
    }

    @Test
    void carriesNoConstantsBecauseTheMessageTypeBelongsToTheConsumer() {
        // NotificationPort 的两个常量必须跟着端口搬，因为消费方（knowledgebase）引用了它们，
        // 不搬则消费方仍要 import 实体。这里相反：admin 用的是自己的字面量
        // "SSE_TEST_BROADCAST"（一条诊断事件的名字，不落库、只走线上），
        // 没有一条 automation 的常量被跨域引用 —— 所以一个都不该出现在端口上。
        long constants = 0;
        for (Field field : NotificationBroadcastPort.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) constants++;
        }
        assertEquals(0, constants,
                "端口上不该有常量。诊断广播的消息类型是 admin 自己的字面量，"
                        + "不属于 automation 的词表。");
    }

    @Test
    void noSignatureMentionsAFrameworkType() {
        // ⭐ 本刀的核心断言。旧文档写着"SSE 广播端口要的是 SseEmitter（框架类型），
        // 形状完全不同" —— 那是对整个类说的，对这条边不成立。
        // 这条断言把"这条边真的不需要框架类型"钉死：谁把 subscribe 搬进来，它就红。
        ContractShape.assertNoFrameworkTypes(NotificationBroadcastPort.class);
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(NotificationBroadcastPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackage() {
        assertTrue(NotificationBroadcastPort.class.isInterface(),
                "端口必须是接口：实现方 implements 它，契约不能带实现。");
        assertEquals("com.xander.lab.automation.spi", NotificationBroadcastPort.class.getPackageName());
    }

    @Test
    void extendsNothingSoNoOtherContractCanLeakInThroughInheritance() {
        // 也顺带挡住"把它与 NotificationPort 合并"的一条路：两者都带 "notification"，
        // 但一个是写持久化通知、一个是向在线连接广播瞬时消息，方法表也不交叠。
        // 合并出来的端口会让 BlogNotificationService 被迫实现"广播"、
        // 让 BlogPlanSseService 被迫实现"落库写通知"。
        assertEquals(0, NotificationBroadcastPort.class.getInterfaces().length,
                "端口不许继承别的接口（包括 NotificationPort）。契约不能依赖契约，"
                        + "继承一条链就能把另一个契约拖进来。");
    }

    /**
     * 按名字取方法，并<b>断言恰好一个</b>。
     *
     * <p>⚠️ 不比"取第一个匹配"更啰嗦：一旦有人往端口上加了一个重载，取第一个匹配会
     * <b>按 JVM 的方法顺序</b>挑一个，下面几条断言就开始检查另一个方法 —— 而它们仍然
     * "通过"。这类失败信息不会出现，因为根本没有失败。断言"恰好一个"把这个不确定性
     * 变成一条会红的判据（注入 A 就是加了一个 {@code default} 重载，它同时踩中这条）。</p>
     */
    private static Method methodNamed(String name) {
        List<Method> matches = Arrays.stream(NotificationBroadcastPort.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> method.getName().equals(name))
                .toList();
        assertEquals(1, matches.size(),
                "端口上应当恰好有一个方法叫 " + name + "，实际 " + matches.size()
                        + " 个。重载会让下面这些按名字取方法的断言悄悄检查错对象。");
        return matches.get(0);
    }
}
