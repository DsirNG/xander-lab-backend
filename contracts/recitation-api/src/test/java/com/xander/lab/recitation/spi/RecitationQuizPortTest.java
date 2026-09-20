package com.xander.lab.recitation.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link RecitationQuizPort} 的形状。
 *
 * <p>为什么这件事只能在这里做：端口变宽（加方法、加 {@code default}、放宽参数）编译得过、
 * 耦合指标一动不动、26 条门禁全绿。见 {@code docs/migration/adr-contract-boundaries.md} §2.6
 * 与 {@code .opencode/skills/backend-norms} 里的"端口变宽没人守"。</p>
 */
class RecitationQuizPortTest {

    @Test
    void declaresExactlyTheTwoKnownMethodsWithTheseExactSignatures() {
        assertEquals(List.of(
                "openQuiz(Long, Long, Long) -> QuizSessionView",
                "recordQuiz(Long, Long, Long, String, List) -> QuizOutcomeView"
        ), ContractShape.declaredSignatures(RecitationQuizPort.class),
                "端口方法集变了。两条方法来自两个工具：QuizKnowledgeTool 要 openQuiz，"
                        + "GradeAnswerTool 要 recordQuiz。"
                        + "提供方公开面里的 listQuizzes（只有 RecitationController 在用）"
                        + "与两参重载 openQuiz(Long, Long)（没有生产调用方）都不该出现在这里。");
    }

    @Test
    void thereIsNoRichMethodToKeepBecauseTheseTwoHadNoSameDomainCaller() {
        // ⚠️ 与 RecitationMaterialPort 的关键差异：那边 getMaterial 有一个同域富方法
        // （控制器在用 RecitationMaterialView 的版本），所以端口占规范名、富方法加 ForView 后缀。
        // 这两条方法的返回类型**没有同域调用方**（只有这两个工具和服务自己的测试），
        // 所以按 ADR §7.5 直接换返回类型、删掉域侧 record，不保留富方法。
        //
        // 这条断言守的是"端口占的是规范名"：如果哪天有人给提供方加回一个
        // QuizSession openQuiz(...) 并把端口方法改名成 openQuizForAgent，名字就漂了。
        assertEquals(QuizSessionView.class, methodNamed("openQuiz").getReturnType(),
                "openQuiz 必须叫这个名字、返回收窄的 QuizSessionView。"
                        + "域侧的 KnowledgeQuizService.QuizSession 已经删掉 —— 它内嵌前端类型"
                        + "KnowledgeQuizAttemptView，搬不进来。");
        assertEquals(QuizOutcomeView.class, methodNamed("recordQuiz").getReturnType(),
                "recordQuiz 必须返回收窄的 QuizOutcomeView（比域侧 QuizOutcome 少一个"
                        + "没有读者的审计列 lastReviewAt）。");
    }

    @Test
    void recordQuizHasNoTotalScoreParameterBecauseTheProviderComputesItFromThePerQuestionCredits() {
        // 工具描述里明确禁止模型自己报总分（"想虚高就得先编出一整套证据"）。
        // 这条约束靠**端口上根本没有那个入参**来保证，而不是靠一句提示词。
        assertEquals(List.of(Long.class, Long.class, Long.class, String.class, List.class),
                List.of(methodNamed("recordQuiz").getParameterTypes()),
                "recordQuiz 的参数表变了。第五个参数必须是 List<GradedItemDraft>（逐题证据），"
                        + "而不是一个 BigDecimal 总分：总分由提供方按每题 credit 算出。");
        // 泛型参数在运行时被擦除，所以只能比对 getGenericParameterTypes() 的**文本**。
        // 比对文本是对的：这条断言要钉的正是"元素类型是不是那个契约 record"。
        assertEquals("java.util.List<com.xander.lab.recitation.spi.GradedItemDraft>",
                methodNamed("recordQuiz").getGenericParameterTypes()[4].getTypeName(),
                "recordQuiz 的第五个参数的元素类型必须是契约里的 GradedItemDraft，"
                        + "不能换成域侧的 GradedItem —— 那是实现类里的嵌套类型，跨域拿不到。");
    }

    @Test
    void theTwoConstantsLiveOnThePortSoTheToolStopsImportingTheImplementationClass() {
        // 常量必须跟着端口搬，否则 GradeAnswerTool 仍然要 import KnowledgeQuizService，
        // 实现语句一条都没少 —— 端口白抽了。判据是"消费方的 import 列表里只剩契约包"。
        assertEquals(20, RecitationQuizPort.MAX_ITEMS,
                "MAX_ITEMS 的值变了。它写进了工具 JSON Schema 的 description（\"最多 N 题\"），"
                        + "也就是写进了模型看到的提示词，改它会改变模型的行为。");
        assertEquals(1000, RecitationQuizPort.MAX_VERDICT_CHARS,
                "MAX_VERDICT_CHARS 的值变了。同上，它写进了模型看到的工具描述。");

        long constants = 0;
        for (Field field : RecitationQuizPort.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            assertTrue(Modifier.isFinal(field.getModifiers()),
                    field.getName() + " 不是 final：端口上的常量必须编译期常量，"
                            + "否则它可以被实现方在运行时改掉。");
            assertTrue(field.getType() == int.class,
                    field.getName() + " 不是 int。端口只带两个上限常量；"
                            + "带一个对象常量等于让契约依赖那个对象的类型。");
            constants++;
        }
        assertEquals(2, constants,
                "端口上的字段数变了。只有 MAX_ITEMS 与 MAX_VERDICT_CHARS 两个："
                        + "它们都从提供方搬来，都是消费方真正引用的。"
                        + "多出来的常量要能说出是哪个消费方在用它。");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(RecitationQuizPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackage() {
        assertTrue(RecitationQuizPort.class.isInterface(),
                "端口必须是接口：实现方 implements 它，契约不能带实现。");
        assertEquals("com.xander.lab.recitation.spi", RecitationQuizPort.class.getPackageName(),
                "包必须是 .spi 而不是 .api：recitation.api 是 recitation 自己的前端 HTTP 层。");
    }

    @Test
    void extendsNothingSoNoOtherContractCanLeakInThroughInheritance() {
        assertEquals(0, RecitationQuizPort.class.getInterfaces().length,
                "端口不许继承别的接口。契约不能依赖契约，继承一条链就能把另一个契约拖进来。");
    }

    @Test
    void noSignatureMentionsAFrameworkType() {
        ContractShape.assertNoFrameworkTypes(RecitationQuizPort.class);
    }

    @Test
    void everyMethodTakesTheCallerIdExplicitlyBecauseThreadLocalDoesNotCrossProcesses() {
        for (var method : RecitationQuizPort.class.getDeclaredMethods()) {
            // ⚠️ 先查参数个数：无参方法直接读 getParameterTypes()[0] 会抛
            // ArrayIndexOutOfBounds，报出来的是"下标越界"而不是"这个端口方法没收调用方 id"。
            assertTrue(method.getParameterCount() > 0,
                    method.getName() + " 没有参数：端口方法必须显式收调用方 id。");
            assertEquals(Long.class, method.getParameterTypes()[0],
                    method.getName() + " 的第一个参数必须是调用方 id。");
        }
    }

    @Test
    void openQuizKeepsTheConversationIdSoGradingCannotBorrowAnotherChatsQuiz() {
        // 三参版本是端口唯一的形式：两参重载（没有生产调用方）用传 null 覆盖同一语义。
        // 保留会话这一维是因为提供方用它做归属校验 —— "评分只能落到这次真实出题上"。
        //
        // ⚠️ 读第 n 个参数之前先查参数个数（待办 59）：参数表缩短时直接读下标抛
        // ArrayIndexOutOfBoundsException —— 报的是"下标越界"而不是"这个方法的参数表变了"。
        Method openQuiz = methodNamed("openQuiz");
        assertTrue(openQuiz.getParameterCount() > 2,
                "openQuiz 的参数表变了：下面读的是第 3 个参数（会话 id），"
                        + "现在只有 " + openQuiz.getParameterCount() + " 个。");
        assertEquals(Long.class, openQuiz.getParameterTypes()[2],
                "openQuiz 的第三个参数必须是会话 id（可为 null）。"
                        + "去掉它会让 recordQuiz 的会话校验失去来源。");
    }

    /**
     * 按名字取方法，并且断言**恰好一个**同名方法。
     *
     * <p>⚠️ 只"按名字取第一个"不够：端口上出现**重载**时它会**按 JVM 的方法顺序**挑一个，
     * 上面的 {@code openQuiz} 断言（读 {@code getParameterTypes()[2]}）就会开始检查另一个
     * 方法 —— 而它仍然"通过"。**没有失败信息，因为根本没有失败。**</p>
     */
    private static Method methodNamed(String name) {
        Method found = null;
        int count = 0;
        for (Method method : RecitationQuizPort.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !name.equals(method.getName())) continue;
            found = method;
            count++;
        }
        assertEquals(1, count,
                "端口上应当恰好有一个方法叫 " + name + "，实际 " + count
                        + " 个。重载会让上面这些按名字取方法的断言悄悄检查错对象。");
        return found;
    }
}
