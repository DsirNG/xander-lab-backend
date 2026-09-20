package com.xander.lab.automation.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link NotificationPort} 的形状。理由与前两个端口测试相同：端口变宽编译得过、
 * 指标不动、门禁不报，只有这里会红。
 *
 * <p>本测试还多钉一件事：两个常量的<b>字面量</b>。它们落库（{@code blog_notification.type} /
 * {@code .resource_type}），前端也按这两个字符串分流 —— 改字面量不是重构，是数据迁移。
 * 常量原本声明在 {@code BlogNotification} 实体上，这次跟着端口搬了过来，所以两边必须对得上：
 * 实体上那两处已删除，值只此一份。</p>
 */
class NotificationPortTest {

    @Test
    void declaresExactlyTheOneKnownMethodWithThisExactSignature() {
        assertEquals(List.of(
                "notifyResource(Long, String, String, String, String, Long) -> Long"
        ), ContractShape.declaredSignatures(NotificationPort.class),
                "端口方法集变了。唯一消费方 knowledgebase 只做一件事：写一条通知。"
                        + "list / unreadCount / markRead / markAllRead 服务的是前端铃铛，"
                        + "不跨域，不该出现在这里。");
    }

    @Test
    void returnsLongSoTheProviderSideIsAPureTypeReplacement() {
        // 与实现同形：BlogNotificationService#notifyResource 逐字相同 ⇒ 提供方只需
        // 加 implements + @Override。若改成 void，Java 不允许同名同参只差返回值，
        // 实现侧就得改名 —— 那是与这条缝无关的改动。
        //
        // ⚠️ 按名字取，不按下标：getDeclaredMethods() 的顺序 JVM 并不保证。
        // 注入验证时往端口加了一个方法，下标 0 就变成了新方法 —— 断言还在"通过"，
        // 只是检查的对象悄悄换了。
        assertEquals(Long.class, methodNamed("notifyResource").getReturnType(),
                "返回值改成 void 会让实现侧必须改名（同名同参只差返回值编译不过）。"
                        + "真要收窄，先确认没有实现方靠这个名字。");
    }

    /**
     * 按名字取方法，并且断言**恰好一个**同名方法。
     *
     * <p>⚠️ 只"按名字取第一个"还不够：端口上出现**重载**时它会**按 JVM 的方法顺序**挑一个，
     * 下面几条断言就开始检查另一个方法 —— 而它们仍然"通过"。
     * **没有失败信息，因为根本没有失败。**</p>
     */
    private static Method methodNamed(String name) {
        Method found = null;
        int count = 0;
        for (Method method : NotificationPort.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !name.equals(method.getName())) continue;
            found = method;
            count++;
        }
        assertEquals(1, count,
                "端口上应当恰好有一个方法叫 " + name + "，实际 " + count
                        + " 个。重载会让下面这些按名字取方法的断言悄悄检查错对象。");
        return found;
    }

    @Test
    void theTwoMovedConstantsKeepTheirExactLiteralValuesBecauseTheyArePersisted() {
        // 这两个字符串写进 blog_notification 表，前端按它们分流。
        assertEquals("KNOWLEDGE_STRUCTURE_PENDING", NotificationPort.TYPE_KNOWLEDGE_STRUCTURE_PENDING,
                "这个字面量落库（blog_notification.type）。改它不是重构，是数据迁移。");
        assertEquals("KNOWLEDGE_FILE", NotificationPort.RESOURCE_KNOWLEDGE_FILE,
                "这个字面量落库（blog_notification.resource_type）。改它不是重构，是数据迁移。");
    }

    @Test
    void carriesExactlyTwoConstantsAndNothingElse() {
        // 端口只带消费方真正用到的东西。automation 自己的四个 TYPE_*（REVIEW_REJECTED /
        // CSDN_FAILED / JUEJIN_FAILED / RUN_FAILED）由 BlogPlanRunService 使用，留在实体上。
        long constants = 0;
        for (Field field : NotificationPort.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) constants++;
        }
        assertEquals(2, constants,
                "端口上的常量数变了。端口只带消费方（knowledgebase）真正用到的常量："
                        + "TYPE_KNOWLEDGE_STRUCTURE_PENDING 与 RESOURCE_KNOWLEDGE_FILE。"
                        + "automation 自己的词表留在 BlogNotification 实体上。");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(NotificationPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackage() {
        assertTrue(NotificationPort.class.isInterface(),
                "端口必须是接口：实现方 implements 它，契约不能带实现。");
        assertEquals("com.xander.lab.automation.spi", NotificationPort.class.getPackageName());
    }

    @Test
    void extendsNothingSoNoOtherContractCanLeakInThroughInheritance() {
        assertEquals(0, NotificationPort.class.getInterfaces().length,
                "端口不许继承别的接口。契约不能依赖契约，继承一条链就能把另一个契约拖进来。");
    }

    @Test
    void noSignatureMentionsAFrameworkType() {
        ContractShape.assertNoFrameworkTypes(NotificationPort.class);
    }

    @Test
    void theMethodTakesTheCallerIdExplicitlyBecauseThreadLocalDoesNotCrossProcesses() {
        for (var method : NotificationPort.class.getDeclaredMethods()) {
            // ⚠️ 先查参数个数：无参方法直接读 getParameterTypes()[0] 会抛
            // ArrayIndexOutOfBounds，报出来的是"下标越界"而不是"这个端口方法没收调用方 id"。
            // 注入验证时踩到过 —— 断言失败的信息必须指向真正的问题。
            assertTrue(method.getParameterCount() > 0,
                    method.getName() + " 没有参数：端口方法必须显式收调用方 id。"
                            + "同进程里可以从 ThreadLocal 取，跨进程没有 ThreadLocal 可传。");
            assertEquals(Long.class, method.getParameterTypes()[0],
                    method.getName() + " 的第一个参数必须是调用方 id（见 EmailReminderPort 上的同一断言）。");
        }
    }
}
