package com.xander.lab.payment.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link BillingPort} 的<b>宽度与形状</b>。
 *
 * <p>端口是"另一个服务能看到 billing 的全部"，所以它变宽就是边界变薄。而变宽这件事
 * 四道防线一道都不响：编译通过（加 {@code default} 连实现都不用改）、
 * {@code api_statements} 不动（那数的是调用点 import，不是方法个数）、
 * 门禁只看 import 与 POM 依赖、没有别的测试会碰新方法。
 * 于是"积分端口"会慢慢长成所有人绕过 billing 边界的新入口 —— 每一次增加单独看都合理，
 * 合起来就是边界消失。</p>
 *
 * <p>这条断言让加方法必须<b>显式改一次测试</b>，把无意识扩张变成有意识决策。
 * 与 {@code MediaAssetPortTest} 同一手法（见 {@code docs/migration/adr-contract-boundaries.md}
 * §3.6 与 §4.7）。</p>
 *
 * <p><b>注入验证（2026-09-17 实测）</b>：给端口加一个
 * {@code default String debugBilling()} 之后，反应堆编译<b>仍然通过</b>、
 * {@code BillingBizTest} 6/6 与 {@code TokenUsageTest} 9/9 全绿，
 * <b>只有本类红 3 条</b>（方法数 7→8、签名表比对、{@code default} 检查）。
 * 这正是它存在的理由。</p>
 *
 * <h2>为什么连参数类型序列一起钉</h2>
 *
 * <p>因为 {@link #chargeTokens} 头四个参数是 {@code (Long, String, String, String)} ——
 * <b>三个相邻的 {@code String}</b>（业务项 / 幂等键 / 业务引用）。把它们换位
 * <b>不会有任何编译错误</b>：幂等键会写到 bizType 列上，而表现是"同一笔业务重复扣费"
 * 或者"对账时按业务项查不到流水"。这类错误不会抛异常，只会让账目悄悄错掉。
 * {@link #chargeFlat} 同理。所以这里钉的是<b>完整签名</b>，不只是方法名。</p>
 */
class BillingPortTest {

    /**
     * 期望的完整签名：方法名 -> {@code "返回类型(参数类型,...)"}。
     *
     * <p>用 {@code getSimpleName()} 而不是 {@code getName()}：能同时区分装箱与原始类型
     * （{@code chargeFlat} 的 {@code points} 是原始 {@code long}，
     * {@code balance} 返回原始 {@code long}），又不至于让字面量长到读不动。</p>
     *
     * <p>顺序即契约。新增一条就要同时回答"谁需要它、为什么不放在调用方"。</p>
     */
    private static final Map<String, String> EXPECTED_SIGNATURES = Map.of(
            "chargeTokens", "void(Long,String,String,String,TokenUsage,String,String)",
            "chargeFlat", "void(Long,String,String,String,long,String)",
            "flatPoints", "long(String)",
            "ensureAvailable", "void(Long)",
            "balance", "long(Long)",
            "grantRegister", "void(Long,String)",
            "transferBalance", "void(Long,Long,String,String)");

    private static Map<String, String> actualSignatures() {
        Map<String, String> found = new TreeMap<>();
        for (Method method : BillingPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            String parameters = Arrays.stream(method.getParameterTypes())
                    .map(Class::getSimpleName)
                    .collect(Collectors.joining(","));
            found.put(method.getName(),
                    method.getReturnType().getSimpleName() + "(" + parameters + ")");
        }
        return found;
    }

    /**
     * 端口必须与预期<b>完全一致</b> —— 多一个方法、少一个方法、换一个参数类型都失败。
     * 失败信息会打印整个 map，看到 diff 就知道动了哪条。
     */
    @Test
    void thePortDeclaresExactlyTheSevenKnownSignatures() {
        assertEquals(EXPECTED_SIGNATURES, actualSignatures(),
                "端口宽度或形状变了。加方法前先问：这是 billing 的数据问题，还是调用方自己该做的事？"
                        + "改参数顺序前先问：这三个相邻的 String 换位之后谁会静默错账？");
    }

    /** 规模也显式钉一次，让"7 个方法"这个事实在失败信息里可见。 */
    @Test
    void thePortHasExactlySevenMethods() {
        assertEquals(7, actualSignatures().size());
    }

    /**
     * 一个 {@code default} 方法可以绕过实现直接扩大契约面，而且不影响任何现有实现 ——
     * 是"变宽"最省力的路径，所以单独钉。
     */
    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        for (Method method : BillingPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertFalse(method.isDefault(),
                    method.getName() + " 是 default 方法：它会在不通知任何实现的情况下扩大契约面。"
                            + "把它做成抽象方法，让每个实现显式表态。");
        }
    }

    @Test
    void thePortIsAnInterfaceInTheContractPackage() {
        assertTrue(BillingPort.class.isInterface(), "端口必须是接口");
        assertEquals("com.xander.lab.payment.api", BillingPort.class.getPackageName());
        assertEquals(0, BillingPort.class.getInterfaces().length,
                "端口不该继承别的接口：那会让宽度从别处漏进来");
    }

    /**
     * 端口签名里不许出现框架类型（Spring / Servlet / MyBatis / Redis），参数与返回值都查。
     *
     * <p>门禁 {@code contract-dependency-boundary} 靠 import 检查兜底，这里再确认一遍
     * 类型本身全是 JDK 或契约类型 —— 因为<b>返回类型最容易漏</b>：
     * {@code ensureAvailable} 的实现抛的是 {@code ResponseStatusException}（Spring 的），
     * 把那个类型写进签名就会让每个依赖方都要有 spring-web；正确的做法是契约只说
     * "会抛非受检异常"，类型留给实现（见端口 javadoc）。</p>
     *
     * <p>⚠️ 数组要取组件类型再判：{@code byte[].class.getName()} 是 {@code "[B"}，
     * 不以 {@code java.} 开头，直接判会误报。</p>
     */
    @Test
    void noMethodSignatureLeaksAFrameworkType() {
        for (Method method : BillingPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;

            Class<?>[] types = new Class<?>[method.getParameterCount() + 1];
            System.arraycopy(method.getParameterTypes(), 0, types, 0, method.getParameterCount());
            types[method.getParameterCount()] = method.getReturnType();

            for (Class<?> raw : types) {
                if (raw == void.class) continue;
                Class<?> type = raw.isArray() ? raw.getComponentType() : raw;
                String name = type.getName();
                assertTrue(type.isPrimitive()
                                || name.startsWith("java.")
                                || name.startsWith("com.xander.lab.payment.api."),
                        method.getName() + " 的签名里有非 JDK / 非契约类型：" + name
                                + "（跨进程传不过去，而且会把框架拖进每个依赖方的 classpath）");
            }
        }
    }

    /**
     * 期望清单本身要自洽：有重复键的话"方法个数 = 清单长度"就会漏掉一个真实方法。
     *
     * <p>{@code Map.of} 在键重复时直接抛 {@code IllegalArgumentException}，所以这条其实
     * 由类初始化兜住了 —— 保留它是因为读的人需要知道这件事是被刻意保证的。</p>
     */
    @Test
    void theExpectedSignatureMapHasNoDuplicates() {
        assertEquals(EXPECTED_SIGNATURES.size(),
                EXPECTED_SIGNATURES.keySet().stream().distinct().count(),
                "EXPECTED_SIGNATURES 里不能有重复方法名");
    }
}
