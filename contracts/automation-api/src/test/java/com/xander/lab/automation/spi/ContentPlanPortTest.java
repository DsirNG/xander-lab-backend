package com.xander.lab.automation.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link ContentPlanPort} 的形状。理由与 {@link EmailReminderPortTest} 相同：
 * 端口变宽编译得过、指标不动、门禁不报，只有这里会红。
 *
 * <p>这个端口尤其值得钉：实现方 {@code BlogContentPlanService} 对<b>自己的控制器</b>公开了
 * 15 个方法，而端口只要 6 个。"顺手把 controller 在用的方法也加进端口"是最可能
 * 发生的走偏，而它不会有任何症状 —— 所以下面那条断言把 15 个里的 9 个明确挡在门外。</p>
 */
class ContentPlanPortTest {

    @Test
    void declaresExactlyTheSixKnownMethodsWithTheseExactSignatures() {
        assertEquals(List.of(
                "create(Long, ContentPlanDraft) -> ContentPlanView",
                "delete(Long, Long) -> void",
                "findByClientRequestId(Long, String) -> ContentPlanView",
                "listAll(Long, String) -> List",
                "update(Long, Long, ContentPlanEdit) -> ContentPlanView",
                "updateStatus(Long, Long, String) -> ContentPlanView"
        ), ContractShape.declaredSignatures(ContentPlanPort.class),
                "端口方法集变了。六个方法对应三个工具真正做的六件事；"
                        + "aiGenerate / createOneShotPlans / list / detail / trigger / listRuns / "
                        + "runDetail / generateTopics / nextRunAfter 都是控制器与调度器要的，"
                        + "智能体不读，不该出现在这里。");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(ContentPlanPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackage() {
        assertTrue(ContentPlanPort.class.isInterface(),
                "端口必须是接口：实现方 implements 它，契约不能带实现。");
        assertEquals("com.xander.lab.automation.spi", ContentPlanPort.class.getPackageName());
    }

    @Test
    void extendsNothingSoNoOtherContractCanLeakInThroughInheritance() {
        assertEquals(0, ContentPlanPort.class.getInterfaces().length,
                "端口不许继承别的接口。契约不能依赖契约，继承一条链就能把另一个契约拖进来。");
    }

    @Test
    void noSignatureMentionsAFrameworkType() {
        ContractShape.assertNoFrameworkTypes(ContentPlanPort.class);
    }

    @Test
    void everyMethodTakesTheCallerIdExplicitlyBecauseThreadLocalDoesNotCrossProcesses() {
        for (var method : ContentPlanPort.class.getDeclaredMethods()) {
            // ⚠️ 先查参数个数：无参方法直接读 getParameterTypes()[0] 会抛 ArrayIndexOutOfBounds，
            // 报出来的是"下标越界"而不是"这个端口方法没收调用方 id"。见 NotificationPortTest。
            assertTrue(method.getParameterCount() > 0,
                    method.getName() + " 没有参数：端口方法必须显式收调用方 id。");
            assertEquals(Long.class, method.getParameterTypes()[0],
                    method.getName() + " 的第一个参数必须是调用方 id（见 EmailReminderPort 上的同一断言）。");
        }
    }

    @Test
    void createTakesAKeyedDraftAndUpdateTakesAnUnkeyedEditSoTheyCannotBeConfused() {
        // 两个写入方法的参数类型刻意不同：创建要幂等键，修改不要。同类型的话，
        // "把创建草稿传给 update"会编译通过 —— 而 update 会静默忽略幂等键。
        //
        // ⚠️ 读第 n 个参数之前先查参数个数：参数表缩短时直接读下标抛
        // ArrayIndexOutOfBoundsException —— 报的是"下标越界"而不是"这个方法的参数表变了"，
        // 失败信息指不到真正的原因。同族的守卫见 NotificationPortTest / EmailReminderPortTest。
        Method create = methodNamed("create");
        assertTrue(create.getParameterCount() > 1,
                "create 的参数表变了：下面读的是第 2 个参数（幂等键 ContentPlanDraft），"
                        + "现在只有 " + create.getParameterCount() + " 个。");
        assertEquals(ContentPlanDraft.class, create.getParameterTypes()[1]);

        Method update = methodNamed("update");
        assertTrue(update.getParameterCount() > 2,
                "update 的参数表变了：下面读的是第 3 个参数（ContentPlanEdit），"
                        + "现在只有 " + update.getParameterCount() + " 个。");
        assertEquals(ContentPlanEdit.class, update.getParameterTypes()[2]);
    }

    /**
     * 按名字取方法，并且断言**恰好一个**同名方法。
     *
     * <p>⚠️ 不要"遍历取第一个"（还把它换算成下标再 {@code getDeclaredMethods()[i]}）。
     * 端口上出现**重载**时，它会**按 JVM 的方法顺序**挑一个，下面几条断言就开始检查另一个
     * 方法 —— 而它们仍然"通过"。**没有失败信息，因为根本没有失败。**</p>
     */
    private static Method methodNamed(String name) {
        Method found = null;
        int count = 0;
        for (Method method : ContentPlanPort.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !name.equals(method.getName())) continue;
            found = method;
            count++;
        }
        assertEquals(1, count,
                "端口上应当恰好有一个方法叫 " + name + "，实际 " + count
                        + " 个。重载会让下面这些按名字取方法的断言悄悄检查错对象。");
        return found;
    }
}
