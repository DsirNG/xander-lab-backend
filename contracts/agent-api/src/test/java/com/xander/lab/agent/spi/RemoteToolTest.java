package com.xander.lab.agent.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link RemoteTool} 的<b>宽度</b>与<b>完整签名</b>。
 *
 * <h2>为什么钉完整签名而不只是方法名</h2>
 * <p>这个接口有四个 {@code String} 返回值的方法和两个收 {@code String} 的方法。
 * 把 {@code approvalReason(String argsJson)} 与 {@code invoke(Long, String, RemoteToolCall)}
 * 的参数顺序换一下、或者给某个方法换一个 {@code String} 的位置，方法名不变、类型全对、
 * 编译通过——结果是"拿参数 JSON 当返回值用"这种静默错账。所以参数顺序必须一起钉。</p>
 *
 * <h2>为什么是这 7 个</h2>
 * <p>正好是消费方适配器要实现的 {@code AgentTool} 成员里除去 {@code reconcile} 的那 7 个：
 * 名字、说明、schema、使用准则、审批理由、是否只读、执行。{@code reconcile}（查证一次被打断
 * 的调用的持久化结果）没有对应方法，因为远端工具查不了——它的副作用在第三方那里，
 * 本平台既没有记录也没有能力去问。</p>
 *
 * <p>顺序即契约：这里是提供方必须提供的全部能力，也是消费方能看到的一切。</p>
 */
class RemoteToolTest {

    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "name() -> String",
            "description() -> String",
            "parametersSchemaJson() -> String",
            "promptText() -> String",
            "approvalReason(String) -> String",
            "readOnly() -> boolean",
            "invoke(Long, String, RemoteToolCall) -> String");

    @Test
    void theContractDeclaresExactlyTheSevenKnownMethodsWithTheseExactSignatures() {
        List<String> actual = ContractShape.declaredSignatures(RemoteTool.class);

        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(), actual,
                "端口宽度或签名变了。加方法前先问：这是消费方真要问的问题，还是它自己该做的事？");
        assertEquals(7, actual.size(), "方法个数必须是 7");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(RemoteTool.class);
    }

    @Test
    void theContractIsAnInterfaceInTheContractPackage() {
        assertTrue(RemoteTool.class.isInterface(), "契约类型必须是接口");
        assertEquals("com.xander.lab.agent.spi", RemoteTool.class.getPackageName());
        assertEquals(0, RemoteTool.class.getInterfaces().length,
                "契约不该继承别的接口：那会让宽度从别处漏进来");
    }

    @Test
    void noMethodSignatureLeaksAFrameworkType() {
        ContractShape.assertNoFrameworkTypes(RemoteTool.class);
    }

    /**
     * schema、参数、返回值三处都必须是 {@code String}，不能是 {@code JsonNode}。
     *
     * <p>这条单独钉是因为它是整个契约模块存在的理由：{@code AgentTool.parametersSchema()}
     * 返回 {@code JsonNode}，正是它当不了契约类型的原因。有人"顺手把它改回 JsonNode"
     * 会让 {@code contract-dependency-boundary} 立刻失败，但那条门禁只报"某处 import 了
     * 非 JDK 类型"，不会告诉后来者这是刻意的设计；这里会。</p>
     */
    @Test
    void everyJsonPayloadTravelsAsTextNotAsAJsonNode() throws ReflectiveOperationException {
        assertString(RemoteTool.class.getMethod("parametersSchemaJson"));
        assertString(RemoteTool.class.getMethod("approvalReason", String.class));
        assertString(RemoteTool.class.getMethod("invoke", Long.class, String.class, RemoteToolCall.class));
    }

    /**
     * {@code invoke} 必须显式收 {@code userId}。
     *
     * <p>工具永远按用户归属执行。这一条在单进程里靠 ThreadLocal 也能糊过去，拆服务之后
     * 一定糊不过去——而"少一个参数"编译是过的（有重载的话），所以在这里钉死。</p>
     */
    @Test
    void invokeTakesAnExplicitUserId() throws ReflectiveOperationException {
        Method invoke = RemoteTool.class.getMethod(
                "invoke", Long.class, String.class, RemoteToolCall.class);

        assertEquals(String.class, invoke.getReturnType(),
                "返回值是结果的 JSON 文本；返回 null 会让调用侧 NPE");
        assertEquals(3, invoke.getParameterCount());
        assertNotNull(invoke.getParameters()[0]);
        assertEquals(Long.class, invoke.getParameterTypes()[0], "第一个参数必须是归属用户");
        assertEquals(RemoteToolCall.class, invoke.getParameterTypes()[2], "第三个参数是回传通道");
    }

    /**
     * {@code readOnly()} 是并发准入条件，必须返回基本类型 {@code boolean}。
     *
     * <p>改成 {@code Boolean} 会多出一个 {@code null} 状态，而调用方一定会写成
     * {@code if (tool.readOnly())} —— 那一刻 NPE，且只在并发路径上出现。</p>
     */
    @Test
    void readOnlyIsAPrimitiveBooleanBecauseItGatesConcurrency() throws ReflectiveOperationException {
        Method readOnly = RemoteTool.class.getMethod("readOnly");

        assertEquals(boolean.class, readOnly.getReturnType());
        assertFalse(readOnly.getReturnType() == Boolean.class);
    }

    @Test
    void theExpectedSignatureListHasNoDuplicates() {
        assertEquals(EXPECTED_SIGNATURES.size(), EXPECTED_SIGNATURES.stream().distinct().count(),
                "EXPECTED_SIGNATURES 里不能有重复项");
    }

    private static void assertString(Method method) {
        assertEquals(String.class, method.getReturnType(),
                method.getName() + " 必须以 JSON 文本传递，不能是 JsonNode 之类的框架类型");
    }
}
