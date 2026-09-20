package com.xander.lab.recitation.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link RecitationMaterialPort} 的形状。
 *
 * <p>为什么这件事只能在这里做：端口变宽（加方法、加 {@code default}、放宽参数）编译得过、
 * 耦合指标一动不动、26 条门禁全绿。
 * 与 {@code .opencode/skills/backend-norms} 里的"端口变宽没人守"。</p>
 */
class RecitationMaterialPortTest {

    @Test
    void declaresExactlyTheFourKnownMethodsWithTheseExactSignatures() {
        assertEquals(List.of(
                "countMaterials(Long, MaterialFilter) -> long",
                "createMaterial(Long, MaterialDraft) -> MaterialView",
                "getMaterial(Long, Long) -> MaterialView",
                "searchMaterials(Long, MaterialFilter) -> List"
        ), ContractShape.declaredSignatures(RecitationMaterialPort.class),
                "端口方法集变了。四条方法来自两个域四个文件的调用点并集："
                        + "agent 的 CreateKnowledgeTool 要 createMaterial，QueryKnowledgeTool 要 "
                        + "searchMaterials + countMaterials；automation 的 BlogContentPlanService 与 "
                        + "BlogPlanRunService 要 getMaterial。"
                        + "提供方公开面里的录音任务、更新、归档、删除、listMaterials 都只服务它自己的"
                        + "控制器，不跨域，不该出现在这里。");
    }

    @Test
    void getMaterialReturnsMaterialViewSoTheProviderSideNeededARenameNotAShapeChange() {
        // 提供方原本是 RecitationMaterialView getMaterial(Long, Long)，给控制器用。
        // 端口要收窄的 MaterialView，同名同参只差返回值编译不过 ⇒ 按既有先例
        // （updateStatusForView）端口占规范名、富方法改名 getMaterialForView。
        //
        // ⚠️ 按名字取，不按下标：getDeclaredMethods() 的顺序 JVM 并不保证。
        assertEquals(MaterialView.class, methodNamed("getMaterial").getReturnType(),
                "getMaterial 的返回值变了。它必须是收窄的 MaterialView："
                        + "recitation.api.RecitationMaterialView 是前端契约，不跨域。");
    }

    @Test
    void theOtherThreeMethodsAreOverloadsSoTheyKeepTheCanonicalName() {
        // createMaterial / searchMaterials / countMaterials 的参数类型与提供方的富方法不同
        // （MaterialDraft vs CreateRecitationMaterialRequest、MaterialFilter vs MaterialQuery），
        // 可以同名重载 ⇒ 不需要加 ForView 后缀。只有 getMaterial 参数完全相同才必须改名。
        //
        // ⚠️ 读第 n 个参数之前先查参数个数：参数表缩短时直接读下标抛
        // ArrayIndexOutOfBoundsException —— 报的是"下标越界"而不是"这个方法的参数表变了"。
        Method createMaterial = methodNamed("createMaterial");
        assertTrue(createMaterial.getParameterCount() > 1,
                "createMaterial 的参数表变了：下面读的是第 2 个参数（MaterialDraft），"
                        + "现在只有 " + createMaterial.getParameterCount() + " 个。");
        assertEquals(MaterialDraft.class, createMaterial.getParameterTypes()[1],
                "createMaterial 的第二个参数必须是 MaterialDraft："
                        + "recitation.api.CreateRecitationMaterialRequest 带 jakarta.validation 注解，"
                        + "契约不许带（ADR-1）。");
        for (String name : List.of("searchMaterials", "countMaterials")) {
            Method method = methodNamed(name);
            assertTrue(method.getParameterCount() > 1,
                    name + " 的参数表变了：下面读的是第 2 个参数（MaterialFilter），"
                            + "现在只有 " + method.getParameterCount() + " 个。");
            assertEquals(MaterialFilter.class, method.getParameterTypes()[1],
                    name + " 的第二个参数必须是 MaterialFilter。");
        }
    }

    @Test
    void theDraftAndTheFilterCannotBeConfusedEvenThoughBothHaveFourComponents() {
        // 两个都是四分量，但类型不同（Draft = 四个 String；Filter = 两个 String + boolean + int），
        // 所以传错在编译期就挡住了。这条断言是防止它们以后漂移成结构相同的类型 ——
        // 那时"传错了"仍然编译得过，只会静默地把筛选条件当成创建入参。
        assertEquals(List.of("title:String", "content:String",
                        "knowledgeType:String", "testMode:String"),
                ContractShape.recordComponents(MaterialDraft.class));
        assertEquals(List.of("search:String", "masteryLevel:String",
                        "dueOnly:boolean", "limit:int"),
                ContractShape.recordComponents(MaterialFilter.class));
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(RecitationMaterialPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackage() {
        assertTrue(RecitationMaterialPort.class.isInterface(),
                "端口必须是接口：实现方 implements 它，契约不能带实现。");
        assertEquals("com.xander.lab.recitation.spi", RecitationMaterialPort.class.getPackageName(),
                "包必须是 .spi 而不是 .api：recitation.api 是 recitation 自己的前端 HTTP 层"
                        + "（5 个控制器/VO/请求体）。声明在那里会让一个包进两个 jar，"
                        + "并把那些控制器判成共享内核。");
    }

    @Test
    void extendsNothingSoNoOtherContractCanLeakInThroughInheritance() {
        assertEquals(0, RecitationMaterialPort.class.getInterfaces().length,
                "端口不许继承别的接口。契约不能依赖契约，继承一条链就能把另一个契约拖进来。");
    }

    @Test
    void noSignatureMentionsAFrameworkType() {
        ContractShape.assertNoFrameworkTypes(RecitationMaterialPort.class);
    }

    @Test
    void everyMethodTakesTheCallerIdExplicitlyBecauseThreadLocalDoesNotCrossProcesses() {
        for (var method : RecitationMaterialPort.class.getDeclaredMethods()) {
            // ⚠️ 先查参数个数：无参方法直接读 getParameterTypes()[0] 会抛
            // ArrayIndexOutOfBounds，报出来的是"下标越界"而不是"这个端口方法没收调用方 id"。
            // 踩到过 —— 断言失败的信息必须指向真正的问题。
            assertTrue(method.getParameterCount() > 0,
                    method.getName() + " 没有参数：端口方法必须显式收调用方 id。"
                            + "同进程里可以从 ThreadLocal 取，跨进程没有 ThreadLocal 可传。");
            assertEquals(Long.class, method.getParameterTypes()[0],
                    method.getName() + " 的第一个参数必须是调用方 id。");
        }
    }

    @Test
    void allProducesAnUnfilteredQueryWhoseLimitIsNotAProviderPageSize() {
        MaterialFilter all = MaterialFilter.all();

        assertNull(all.search(), "all() 不该带搜索词。");
        assertNull(all.masteryLevel(), "all() 不该带掌握等级过滤。");
        assertFalse(all.dueOnly(), "all() 不该只看到期条目。");
        assertEquals(Integer.MAX_VALUE, all.limit(),
                "all() 的 limit 必须是 MAX_VALUE 而不是硬编码提供方的分页上限："
                        + "提供方会把上限夹到自己的页大小，所以检索与统计的结果都一样，"
                        + "但契约里不该出现提供方可以改的策略常量 —— 它一旦从 100 改成 50，"
                        + "硬编码 100 的写法就会与提供方自己的 all() 悄悄分家。");
    }

    /**
     * 按名字取方法，并且断言**恰好一个**同名方法。
     *
     * <p>⚠️ 只"按名字取第一个"不够：端口上出现**重载**时它会**按 JVM 的方法顺序**挑一个，
     * 下面的断言就开始检查另一个方法 —— 而它们仍然"通过"。
     * **没有失败信息，因为根本没有失败。**</p>
     */
    private static Method methodNamed(String name) {
        Method found = null;
        int count = 0;
        for (Method method : RecitationMaterialPort.class.getDeclaredMethods()) {
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
