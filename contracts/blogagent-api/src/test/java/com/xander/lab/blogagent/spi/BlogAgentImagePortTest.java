package com.xander.lab.blogagent.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link BlogAgentImagePort} 的<b>完整签名</b>。
 *
 * <p>为什么只有反射能守：端口"变宽"（加方法、加 {@code default}、改参数表）不会触发任何一道
 * 其它防线 —— 编译过、耦合指标一动不动、26 条门禁全绿、单元测试也全绿。前面几个契约各实测过一次，
 * 这是第六次。</p>
 *
 * <p>⚠️ 与 {@link BlogAgentPortTest} 的区别：那个端口每个方法都以 {@code userId} 打头，
 * 这个端口<b>一个都没有</b>。所以守卫是反的 —— 见
 * {@link #takesNoCallerIdAndThatIsDeliberate()}。</p>
 */
class BlogAgentImagePortTest {

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
        for (Method method : BlogAgentImagePort.class.getDeclaredMethods()) {
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
    void hasExactlyTheseTwoMethods() {
        assertEquals(List.of(
                        "generate(String) -> BlogAgentImage",
                        "isEnabled() -> boolean"),
                ContractShape.declaredSignatures(BlogAgentImagePort.class),
                "端口的方法表变了。加方法就是变宽 —— 它会让每个实现方（包括未来的服务）多一份"
                        + "必须实现的义务，而没有任何一道防线会报。真要有意加，就把预期清单一起改，"
                        + "并在设计文档里记下为什么。");
    }

    @Test
    void declaresNoDefaultMethods() {
        ContractShape.assertNoDefaultMethods(BlogAgentImagePort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackageAndExtendsNothing() {
        assertTrue(BlogAgentImagePort.class.isInterface(), "契约端口必须是接口。");
        assertEquals("com.xander.lab.blogagent.spi", BlogAgentImagePort.class.getPackageName(),
                "包名必须是 blogagent.spi —— blogagent.api 是前端 HTTP 层，塞进去会让一个包进两个 jar。");
        assertEquals(0, BlogAgentImagePort.class.getInterfaces().length,
                "端口不许 extends 别的接口：契约不能依赖契约，继承会把别的契约的方法并进来。"
                        + "尤其不许 extends BlogAgentPort —— 那两个端口形状不同，合并会让"
                        + "「端口宽度 = 调用方适配器被迫实现的成员集」这条判据失效。");
    }

    @Test
    void carriesNoFrameworkTypesInAnySignature() {
        ContractShape.assertNoFrameworkTypes(BlogAgentImagePort.class);
    }

    /**
     * ⚠️ 这是本项目<b>唯一</b>一个不收 {@code userId} 的端口，而这条断言把它钉成一个决定。
     *
     * <p>理由（也写在端口的 Javadoc 里）：提供方是一次<b>无状态的模型调用</b> ——
     * 不查库、不读 {@code UserContext}、不按用户分片，给它一个 {@code userId} 只会得到一个
     * 没人用的参数。这一路上真正需要身份的两步都在别的端口上，而且都显式收了：
     * {@code MediaAssetPort.recordGenerated(userId, ...)} 决定图片存进谁的名下，
     * {@code BillingPort.chargeTokens(userId, ...)} 决定扣谁的积分。</p>
     *
     * <p>为什么必须写成反向断言："别的端口都收 userId"这条一致性会诱使后来者顺手补一个上去，
     * 而补完之后编译过、测试绿、指标不动 —— 只有一条没人读的参数留在契约里，
     * 让下一个读代码的人去找它到底在哪儿被用上。</p>
     */
    @Test
    void takesNoCallerIdAndThatIsDeliberate() {
        ContractShape.assertNoParameterIsNamed(BlogAgentImagePort.class, "userId");
    }

    /**
     * {@code generate} 收的是<b>拼好的 prompt</b>，不是风格常量、不是结构化的生成请求。
     *
     * <p>风格措辞是渲染方的策略：独立配图工具用 {@code ImageGenerationTool.TOOL_IMAGE_STYLE}，
     * 同域的博客插图用 {@code BlogAgentService} 里那段"准确、克制、专业的知识插图"。
     * 两者刻意不同，端口不该替任何一方决定措辞，也不该把任一份措辞变成契约的一部分。</p>
     */
    @Test
    void generateTakesAPlainPromptString() {
        Method generate = methodNamed("generate");
        assertEquals(1, generate.getParameterCount(), "generate 只收 prompt 一个参数。");
        assertEquals(String.class, generate.getParameterTypes()[0],
                "prompt 必须是 String：风格前缀由调用方自己拼 —— 端口不带渲染方的策略常量。");
        assertEquals("prompt", generate.getParameters()[0].getName(),
                "参数名必须是 prompt —— 反射读的是名字，改名会让这条断言变红。");
        assertEquals(BlogAgentImage.class, generate.getReturnType(),
                "返回类型必须是契约记录 BlogAgentImage，不能是提供方的 "
                        + "BlogAgentImageClient.GeneratedImage（它带 ModelUsage，且住在 infrastructure 包里）。");
    }

    /** {@code isEnabled} 必须是 {@code boolean} 而不是 {@code Boolean}：没有"未知"这个状态。 */
    @Test
    void isEnabledReturnsAPrimitiveBoolean() {
        Method isEnabled = methodNamed("isEnabled");
        assertEquals(0, isEnabled.getParameterCount(), "isEnabled 不收参数。");
        assertEquals(boolean.class, isEnabled.getReturnType(),
                "返回类型必须是 boolean。提供方未配置模型时返回 false 而不是抛异常 —— "
                        + "消费方据此给出「未配置模型」这类可读的失败，而不是把配置缺失报成调用故障。"
                        + "用装箱 Boolean 会多出一个没有语义的 null 状态。");
    }
}
