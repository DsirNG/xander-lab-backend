package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link PublicationPort} 的<b>宽度</b>与<b>完整签名</b>。
 *
 * <p>理由与 {@code ContentPostPortTest} 完全一样：端口<b>变宽</b>不会触发任何一道防线
 * （编译 / 指标 / 门禁 / 其他测试），加一个 {@code default} 方法就能在不通知任何实现的情况下
 * 扩大契约面。这里是"发布与查证"的边界，变宽的后果是内容域的编排能力被逐个搬空。</p>
 *
 * <h2>为什么钉完整签名而不只是方法名</h2>
 * <p>{@code findSucceeded(Long userId, Long taskId, String platform)} 里
 * 两个 {@code Long} 对调，方法名不变、类型全对、编译通过 —— 结果是"用 taskId 当用户 id
 * 去查发布记录"，通常查不到（静默返回 {@code null}），偶尔查到别人的。
 * 所以参数顺序必须一起钉。</p>
 *
 * <p><b>⚠️ 数组要先取组件类型</b>（{@code byte[].class.getName()} 是 {@code "[B"}），
 * 本端口目前没有数组参数，规则先写好避免以后误判。</p>
 */
class PublicationPortTest {

    /**
     * 顺序即契约：这里是实现必须提供的全部能力，也是调用方能看到的一切。
     *
     * <p>刻意<b>不在</b>清单上的：{@code enforceCsdnSyncPolicy}（私有）、
     * 以及同域的 {@code BlogPlanRunService} / {@code BlogAgentService} 走的其他发布能力
     * —— 它们与内容域同在 {@code dinqor-content-automation}，拆完还在一个进程里。</p>
     */
    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "publish(PublishCommand, String) -> PublishResult",
            "findSucceeded(Long, Long, String) -> PublishResult");

    @Test
    void thePortDeclaresExactlyTheTwoKnownMethodsWithTheseExactSignatures() {
        List<String> actual = declaredSignatures();

        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(), actual,
                "端口宽度或签名变了。加方法前先问：这是内容域的编排问题，还是调用方自己该做的事？");
        assertEquals(2, actual.size(), "端口方法个数必须是 2");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        for (Method method : PublicationPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertFalse(method.isDefault(),
                    method.getName() + " 是 default 方法：它会在不通知任何实现的情况下扩大契约面。"
                            + "把它做成抽象方法，让每个实现显式表态。");
        }
    }

    @Test
    void thePortIsAnInterfaceInTheContractPackage() {
        assertTrue(PublicationPort.class.isInterface(), "端口必须是接口");
        assertEquals("com.xander.lab.content.api", PublicationPort.class.getPackageName());
        assertEquals(0, PublicationPort.class.getInterfaces().length,
                "端口不该继承别的接口：那会让宽度从别处漏进来");
    }

    @Test
    void noMethodSignatureLeaksAFrameworkType() {
        for (Method method : PublicationPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;

            assertContractType(method, method.getReturnType(), "返回值");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertContractType(method, parameter, "参数");
            }
        }
    }

    /**
     * {@code findSucceeded} 必须真的收三个参数，而且第二个是 {@code Long} 的 {@code taskId}：
     * 幂等键是 {@code (taskId, platform)}，少了 {@code taskId} 就查不出唯一一条记录。
     */
    @Test
    void findSucceededTakesTheSameIdempotencyKeyAsPublish() throws ReflectiveOperationException {
        Method findSucceeded = PublicationPort.class.getMethod(
                "findSucceeded", Long.class, Long.class, String.class);

        assertEquals(PublishResult.class, findSucceeded.getReturnType(),
                "查证是纯查询，返回既有结果；没有记录时返回 null，不抛异常");
        assertEquals(3, findSucceeded.getParameterCount());
    }

    @Test
    void theExpectedSignatureListHasNoDuplicates() {
        assertEquals(EXPECTED_SIGNATURES.size(), EXPECTED_SIGNATURES.stream().distinct().count(),
                "EXPECTED_SIGNATURES 里不能有重复项");
    }

    private static void assertContractType(Method method, Class<?> raw, String role) {
        Class<?> type = raw.isArray() ? raw.getComponentType() : raw;
        String name = type.getName();
        assertTrue(type.isPrimitive()
                        || name.startsWith("java.")
                        || name.startsWith("com.xander.lab.content.api."),
                method.getName() + " 的" + role + "里有非 JDK / 非契约类型：" + name
                        + "（跨进程传不过去，而且会把框架拖进每个依赖方的 classpath）");
    }

    private static List<String> declaredSignatures() {
        return Arrays.stream(PublicationPort.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(PublicationPortTest::signature)
                .sorted()
                .toList();
    }

    private static String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(PublicationPortTest::typeName)
                .collect(Collectors.joining(", "));
        return method.getName() + "(" + parameters + ") -> " + typeName(method.getReturnType());
    }

    private static String typeName(Class<?> type) {
        return type.isArray() ? typeName(type.getComponentType()) + "[]" : type.getSimpleName();
    }
}
