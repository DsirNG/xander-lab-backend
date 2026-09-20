package com.xander.lab.blogagent.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link BlogAgentPort} 的<b>完整签名</b>。
 *
 * <p>为什么只有反射能守：端口"变宽"（加方法、加 {@code default}、改参数表）不会触发任何一道
 * 其它防线 —— 编译过、耦合指标一动不动、26 条门禁全绿、单元测试也全绿。
 * 前面几个契约各实测过一次，这是第五次。</p>
 */
class BlogAgentPortTest {

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
        for (Method method : BlogAgentPort.class.getDeclaredMethods()) {
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
    void hasExactlyTheseFiveMethods() {
        assertEquals(List.of(
                        "create(Long, BlogAgentTaskDraft) -> BlogAgentTaskView",
                        "findPublishedPost(Long, Long) -> BlogAgentPostView",
                        "findReadyTaskByClientRequestId(Long, String) -> BlogAgentTaskView",
                        "publish(Long, Long) -> BlogAgentPostView",
                        "runStream(Long, Long, BiConsumer, BooleanSupplier) -> BlogAgentTaskView"),
                ContractShape.declaredSignatures(BlogAgentPort.class),
                "端口的方法表变了。加方法就是变宽 —— 它会让每个实现方（包括未来的服务）多一份"
                        + "必须实现的义务，而没有任何一道防线会报。真要有意加，就把预期清单一起改，"
                        + "并在 ADR 里记下为什么。");
    }

    @Test
    void declaresNoDefaultMethods() {
        ContractShape.assertNoDefaultMethods(BlogAgentPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackageAndExtendsNothing() {
        assertTrue(BlogAgentPort.class.isInterface(), "契约端口必须是接口。");
        assertEquals("com.xander.lab.blogagent.spi", BlogAgentPort.class.getPackageName(),
                "包名必须是 blogagent.spi —— blogagent.api 是前端 HTTP 层，塞进去会让一个包进两个 jar。");
        assertEquals(0, BlogAgentPort.class.getInterfaces().length,
                "端口不许 extends 别的接口：契约不能依赖契约，继承会把别的契约的方法并进来。");
    }

    @Test
    void everyMethodTakesTheCallerIdExplicitlyAsItsFirstParameter() {
        ContractShape.assertFirstParameterIsTheCallerId(BlogAgentPort.class);
    }

    @Test
    void carriesNoFrameworkTypesInAnySignature() {
        ContractShape.assertNoFrameworkTypes(BlogAgentPort.class);
    }

    /**
     * 参数顺序：这两个方法的参数表**逐字相同**（{@code (Long, Long)}），只有名字和返回类型不同。
     *
     * <p>提供方那边它们是 {@code (taskId, userId)}，端口归一成 {@code (userId, taskId)}。
     * 这条断言只钉住"第一个参数叫 userId"；真正的换位由 {@code BlogAgentPortMappingTest}
     * 用互不相同的字面量钉住 —— 反射看不见方法体。</p>
     */
    @Test
    void theTwoTaskIdMethodsPutTheCallerIdFirst() {
        for (String name : List.of("findPublishedPost", "publish")) {
            Method method = methodNamed(name);
            // ⚠️ 读第 n 个参数之前先查参数个数：参数表缩短时直接读下标抛
            // ArrayIndexOutOfBoundsException —— 报的是"下标越界"而不是"这个方法的参数表变了"。
            // 这一条守卫覆盖下面四个读数（类型 [0]/[1] 与参数名 [0]/[1]）。
            assertTrue(method.getParameterCount() > 1,
                    name + " 的参数表变了：下面读的是前两个参数的类型与名字，"
                            + "现在只有 " + method.getParameterCount() + " 个。");
            assertEquals(Long.class, method.getParameterTypes()[0]);
            assertEquals(Long.class, method.getParameterTypes()[1]);
            assertEquals("userId", method.getParameters()[0].getName(),
                    name + " 的第一个参数必须是 userId。提供方那边是 (taskId, userId)，"
                            + "端口统一成 (userId, taskId) —— 与四个既有契约一致。");
            assertEquals("taskId", method.getParameters()[1].getName(),
                    name + " 的第二个参数必须是 taskId。");
        }
    }

    /**
     * {@code runStream} 的回调必须是 JDK 类型，而且第三个参数是"事件 + 载荷"两元组。
     *
     * <p>载荷是 {@code String} 而不是某个结构化类型：stage 事件的载荷形如
     * {@code "write|正在撰写文章"}，是提供方推过来的**文本**。把它拆成结构化类型等于让契约
     * 假装知道渲染格式，而 stage 的翻译是**调用方的策略**（端口不带渲染方的策略常量）。</p>
     */
    @Test
    void runStreamTakesAJdkEventCallbackAndACancellationProbe() {
        Method runStream = methodNamed("runStream");
        // ⚠️ 这条参数个数断言必须**排在两个下标读数之前**：
        // 否则参数表缩短时先抛的是 ArrayIndexOutOfBoundsException，这条消息根本轮不到说 ——
        // 判据在、但排在症状后面，等于没有。
        assertEquals(4, runStream.getParameterCount(),
                "runStream 是四参形式。三参的 Consumer<String> 重载只服务同域的前端 HTTP 层，"
                        + "不上端口 —— 端口只收跨域真正用到的形状。");
        assertEquals(BiConsumer.class, runStream.getParameterTypes()[2],
                "第三个参数必须是 BiConsumer<String, String>（event + data）。");
        assertEquals(BooleanSupplier.class, runStream.getParameterTypes()[3],
                "第四个参数必须是 BooleanSupplier（协作式取消查询）。");
    }

    /** 端口的返回类型必须是契约记录，不能是域侧实体（那会把 MyBatis 拖过边界）。 */
    @Test
    void returnsContractRecordsNotDomainEntities() {
        assertEquals(BlogAgentTaskView.class, methodNamed("create").getReturnType());
        assertEquals(BlogAgentTaskView.class, methodNamed("runStream").getReturnType());
        assertEquals(BlogAgentTaskView.class,
                methodNamed("findReadyTaskByClientRequestId").getReturnType());
        assertEquals(BlogAgentPostView.class, methodNamed("findPublishedPost").getReturnType());
        assertEquals(BlogAgentPostView.class, methodNamed("publish").getReturnType());
    }

    /**
     * {@code create} 收的是 {@link BlogAgentTaskDraft}，不是域侧的
     * {@code blogagent.api.BlogAgentTaskCreateRequest}。
     *
     * <p>后者带 {@code @NotBlank} / {@code @Size} —— 带框架注解的类型不许进契约。</p>
     */
    @Test
    void createTakesTheContractDraftNotTheValidatedRequest() {
        Method create = methodNamed("create");
        assertEquals(2, create.getParameterCount());
        assertEquals(BlogAgentTaskDraft.class, create.getParameterTypes()[1],
                "第二个参数必须是 BlogAgentTaskDraft。域侧的 BlogAgentTaskCreateRequest 带 "
                        + "jakarta.validation 注解，按 ADR-1 不能进契约。");
        assertFalse(create.getParameterTypes()[1].getName().startsWith("com.xander.lab.blogagent.api."),
                "契约不许收 blogagent.api.* —— 那是前端 HTTP 层。");
    }
}
