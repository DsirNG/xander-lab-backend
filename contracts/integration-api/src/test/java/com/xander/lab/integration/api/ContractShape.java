package com.xander.lab.integration.api;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约形状的共用断言。与 {@code blogagent-api} 里那个同名类是同一条判据的第二次落地
 * （两个契约模块在 test scope 上互不可见，只能各带一份）。
 *
 * <p>存在的理由：端口的"变宽"不会触发任何一道其它防线 —— 编译过、耦合指标一动不动、
 * 26 条门禁全绿、单元测试也全绿。只有反射能守。</p>
 */
final class ContractShape {

    private ContractShape() {
    }

    /** 方法名 + 参数表 + 返回类型，按名字排序。数组先取组件类型（{@code byte[].class.getName()} 是 {@code "[B"}）。 */
    static List<String> declaredSignatures(Class<?> contract) {
        return Arrays.stream(contract.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(ContractShape::signature)
                .sorted()
                .toList();
    }

    static String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(ContractShape::typeName)
                .collect(Collectors.joining(", "));
        return method.getName() + "(" + parameters + ") -> " + typeName(method.getReturnType());
    }

    static String typeName(Class<?> type) {
        return type.isArray() ? typeName(type.getComponentType()) + "[]" : type.getSimpleName();
    }

    /** {@code default} 方法会在不通知任何实现的情况下扩大契约面。 */
    static void assertNoDefaultMethods(Class<?> contract) {
        for (Method method : contract.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertFalse(method.isDefault(),
                    method.getName() + " 是 default 方法：它会在不通知任何实现的情况下扩大契约面。"
                            + "把它做成抽象方法，让每个实现显式表态。");
        }
    }

    /**
     * 每个方法的第一个参数必须叫 {@code userId}。
     *
     * <p>⚠️ 这条断言的是<b>参数名</b>，不是类型。只断言类型 {@code Long} 是不够的：
     * {@code sync(Long taskId, Long userId)} 两个都是 {@code Long}，
     * 类型断言<b>绿着过</b>，而语义是"用 taskId 当用户 id 去查"。{@code -parameters} 已开，
     * 所以参数名在运行时可见 —— 这是把"判据只朝一个方向看"堵住的那一次。</p>
     *
     * <p>⚠️ 断言首参前先查 {@code getParameterCount() > 0}：无参方法上取
     * {@code getParameters()[0]} 是越界，失败信息会指向错误的地方。</p>
     */
    static void assertFirstParameterIsTheCallerId(Class<?> contract) {
        for (Method method : contract.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertTrue(method.getParameterCount() > 0,
                    method.getName() + " 一个参数都没有，不可能显式收到调用方身份。"
                            + "跨进程后提供方读不到调用方的 ThreadLocal，身份只能靠参数传。");
            assertEquals("userId", method.getParameters()[0].getName(),
                    method.getName() + " 的第一个参数必须叫 userId（实际叫 "
                            + method.getParameters()[0].getName() + "）。"
                            + "它必须是调用方身份 —— 归属判定全靠它。");
        }
    }

    /** 签名里不许出现非 JDK、非本契约包的类型：跨进程传不过去，还会把框架拖进每个依赖方的 classpath。 */
    static void assertNoFrameworkTypes(Class<?> contract) {
        String contractPackage = contract.getPackageName() + ".";
        for (Method method : contract.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertContractType(method, method.getReturnType(), "返回值", contractPackage);
            for (Class<?> parameter : method.getParameterTypes()) {
                assertContractType(method, parameter, "参数", contractPackage);
            }
        }
    }

    private static void assertContractType(Method method, Class<?> raw, String role, String contractPackage) {
        Class<?> type = raw.isArray() ? raw.getComponentType() : raw;
        String name = type.getName();
        assertTrue(type.isPrimitive() || name.startsWith("java.") || name.startsWith(contractPackage),
                method.getName() + " 的" + role + "里有非 JDK / 非本契约类型：" + name
                        + "（跨进程传不过去，而且会把框架拖进每个依赖方的 classpath）");
    }
}
