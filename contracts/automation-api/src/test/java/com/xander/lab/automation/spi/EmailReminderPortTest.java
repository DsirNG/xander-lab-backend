package com.xander.lab.automation.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link EmailReminderPort} 的形状。
 *
 * <p>这些断言存在的唯一理由是：<b>端口变宽没有任何别的防线</b>。加一个方法、加一个
 * {@code default}、把参数换成框架类型，三件事都能编译通过、都不改跨域耦合指标、都不触发
 * 任何一条门禁。实测结论就是给端口加一个 {@code default} 方法后
 * 26 条门禁<b>全绿</b>，只有这类反射测试会红。所以这里钉的是<b>完整签名</b>，
 * 不只是方法个数 —— 三个相邻的 {@code Long} 或 {@code String} 换位同样不报错，只静默错账。</p>
 */
class EmailReminderPortTest {

    @Test
    void declaresExactlyTheFiveKnownMethodsWithTheseExactSignatures() {
        assertEquals(List.of(
                "create(Long, EmailReminderDraft) -> EmailReminderView",
                "delete(Long, Long) -> void",
                "findByClientRequestId(Long, String) -> EmailReminderView",
                "listAll(Long, String, String) -> List",
                "updateStatus(Long, Long, String) -> EmailReminderView"
        ), ContractShape.declaredSignatures(EmailReminderPort.class),
                "端口方法集变了。它是调用方真正用到的那五个：加方法等于扩大永久暴露面，"
                        + "删方法会打断调用方。改签名前先想清楚是不是真的必要。");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(EmailReminderPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackage() {
        assertTrue(EmailReminderPort.class.isInterface(),
                "端口必须是接口：实现方 implements 它，契约不能带实现。");
        assertEquals("com.xander.lab.automation.spi", EmailReminderPort.class.getPackageName());
    }

    @Test
    void extendsNothingSoNoOtherContractCanLeakInThroughInheritance() {
        assertEquals(0, EmailReminderPort.class.getInterfaces().length,
                "端口不许继承别的接口。契约不能依赖契约，继承一条链就能把另一个契约拖进来。");
    }

    @Test
    void noSignatureMentionsAFrameworkType() {
        ContractShape.assertNoFrameworkTypes(EmailReminderPort.class);
    }

    @Test
    void everyMethodTakesTheCallerIdExplicitlyBecauseThreadLocalDoesNotCrossProcesses() {
        for (var method : EmailReminderPort.class.getDeclaredMethods()) {
            // ⚠️ 先查参数个数：无参方法直接读 getParameterTypes()[0] 会抛 ArrayIndexOutOfBounds，
            // 报出来的是"下标越界"而不是"这个端口方法没收调用方 id"。见 NotificationPortTest。
            assertTrue(method.getParameterCount() > 0,
                    method.getName() + " 没有参数：端口方法必须显式收调用方 id。");
            assertEquals(Long.class, method.getParameterTypes()[0],
                    method.getName() + " 的第一个参数必须是调用方 id。同进程里可以从 "
                            + "ThreadLocal 取，跨进程没有 ThreadLocal 可传 —— 不收 id 的端口"
                            + "只是在描述一个无法跨进程的调用。");
        }
    }

    @Test
    void exposesTheTwoStatusConstantsTheCallersSwitchOn() throws Exception {
        assertTrue(Modifier.isStatic(EmailReminderPort.class.getDeclaredField("STATUS_PENDING").getModifiers()));
        assertEquals("PENDING", EmailReminderPort.STATUS_PENDING);
        assertEquals("PAUSED", EmailReminderPort.STATUS_PAUSED,
                "这两个常量原本挂在 EmailReminderService 上。它们必须跟着端口走，否则调用方"
                        + "为了拿一个字符串又要 import 实现类，这次抽取就白做了。");
    }

    @Test
    void theStatusConstantsAreTheOnlyFieldsOnThePort() throws Exception {
        assertEquals(2, EmailReminderPort.class.getDeclaredFields().length,
                "端口上只应有 STATUS_PENDING 与 STATUS_PAUSED 两个常量。多出来的字段会成为"
                        + "第二份可以漂移的常量表 —— 需要常量就放在实现方或调用方，"
                        + "除非它确实是契约的一部分。");
    }
}
