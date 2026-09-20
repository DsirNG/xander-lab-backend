package com.xander.lab.integration.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 钉住 {@link CsdnBlogSyncPort} 与 {@link JuejinBlogSyncPort} 之间**哪些方法可以互换**。
 *
 * <h2>为什么需要一个专门的测试</h2>
 * <p>两个端口的方法**名逐字相同**（{@code sync} 与 {@code findSucceeded}）。这类
 * "同形不同源"是静默换位点的第三种形态（见 {@code notes/contract-extraction.md}）：
 * 把 {@code csdnPort.x(...)} 写成 {@code juejinPort.x(...)} 之后
 * <b>编译通过、耦合指标一动不动、26 条门禁全绿</b>，只有行为断言能抓 ——
 * 而抓它的前提是"断言选的值分得开两个被测对象"，也就是不对称桩。</p>
 *
 * <p>今天两个端口里只有 {@code findSucceeded} 真的同形：{@code sync} 的参数表不同
 * （CSDN 2 个参数、掘金 4 个），所以那个方法上写反接收者<b>编译不过</b>。
 * 这是一道免费的编译期保护，而且是"参数表恰好不同"的<b>副产品</b>，不是设计出来的。
 * 本测试把这件事变成判据：<b>可互换的方法集必须恰好等于 {@link #INTERCHANGEABLE}</b>。</p>
 *
 * <p>⚠️ 判据是<b>双向</b>的。集合变大（多一个同形方法 ⇒ 多一处静默换位点）要报；
 * 变小（{@code sync} 变得同形，或 {@code findSucceeded} 不再同形）也要报 ——
 * 后者是好事，但提示文本与两个端口的 javadoc 要一起删掉，
 * 否则下一个人会去守一个已经不存在的坑。</p>
 *
 * <p>⚠️ 这个测试<b>不能</b>证明"消费方用了不对称桩"，它只说得清坑在哪。管这件事的是另外两条：
 * {@code apps/core-host} 的 {@code DualSyncPortConsumerTest} 在类路径上出现同时持有两个端口的
 * 类时变红；而那个类自己的测试里必须有不对称桩。今天这样的类<b>恰好一个</b> ——
 * {@code com.xander.lab.agent.application.PublishPostTool}，它的两条不对称桩用例在
 * {@code PublishPostToolTest}。</p>
 *
 * <p>⭐ 本类出现之前，纸面上的说法是"今天没有调用点会写反，只是巧合"。
 * 把 {@code DualSyncPortConsumerTest} 一跑，它立刻点名 {@code PublishPostTool} ——
 * 那个类<b>早就</b>同时持有两个端口，而且 {@code reconcile} 里两行 {@code findSucceeded}
 * 相邻、参数表逐字相同。写反的机会一直存在，只是从来没有东西会报错。
 * <b>先写判据、再用判据去测自己相信的话</b>，代价是十分钟，收益是发现结论是错的。</p>
 */
class BlogSyncPortInterchangeabilityTest {

    /**
     * 两个端口上签名<b>逐字相同</b>的方法。
     *
     * <p>⚠️ 这是本测试的<b>基线</b>，它记的是"哪里需要不对称桩"，不是"哪里现在碰巧安全"。
     * 不要为了让它变绿而改这个常量。</p>
     */
    private static final Set<String> INTERCHANGEABLE = Set.of("findSucceeded");

    @Test
    void onlyTheListedMethodsAreInterchangeableBetweenTheTwoPorts() {
        assertEquals(new TreeSet<>(INTERCHANGEABLE), interchangeableMethods(),
                "两个同步端口之间「可互换的方法集」变了。\n"
                        + "· 变**多**：一个方法在两个端口上签名逐字相同 ⇒ 把 csdnPort.x(...) 写成 "
                        + "juejinPort.x(...) 会**编译通过**。给那个方法补一条不对称桩的行为测试"
                        + "（一个平台命中、另一个不命中，两个方向各一条），再把它加进 INTERCHANGEABLE。\n"
                        + "· 变**少**：编译期保护回来了（好事）—— 但如果少的是 findSucceeded，"
                        + "说明两个 findSucceeded 已经不同形，本测试的 javadoc 与两个端口里那句提示"
                        + "要一起删掉，否则下一个人会去守一个不存在的坑。\n"
                        + "⚠️ 不要为了让它变绿直接改 INTERCHANGEABLE —— 那是这份判据的基线。");
    }

    /**
     * {@code findSucceeded} 同形，所以它是**两个端口里唯一的静默换位点**。
     *
     * <p>这条断言的是"这个坑确实存在"。它的价值在于<b>名字与失败信息</b>：
     * 一个将来要同时接入两个端口的调用方，读到这里就知道该给哪个方法配不对称桩。</p>
     *
     * <p>不对称桩的具体形状（凭证端口那类不对称桩已经交过学费）：两个桩必须取
     * <b>不同的值</b>，例如 CSDN 桩返回一个结果、掘金桩返回 {@code null}（或反过来）。
     * 两个桩取同一个值时，接收者写反<b>照样绿</b> —— 那不是"断言写少了"，
     * 是"断言选的值分不开两个被测对象"。</p>
     */
    @Test
    void findSucceededIsInterchangeableWhichIsWhyADualPortCallerNeedsAsymmetricStubs() {
        Map<String, String> csdn = signaturesByName(CsdnBlogSyncPort.class);
        Map<String, String> juejin = signaturesByName(JuejinBlogSyncPort.class);
        assertEquals(csdn.get("findSucceeded"), juejin.get("findSucceeded"),
                "findSucceeded 不再同形了。如果这是有意的，把 INTERCHANGEABLE 改成空集、"
                        + "并删掉本方法与两个端口 javadoc 里那段「同形不同源」的提示；"
                        + "如果不是有意的，说明有人动了参数表 —— 先确认它没有把归属判定搞坏。");
        assertEquals("findSucceeded(Long, Long) -> BlogSyncResult", csdn.get("findSucceeded"),
                "findSucceeded 的签名变了。");
    }

    /**
     * {@code sync} 不同形 —— 而且差别必须在<b>参数表</b>上。
     *
     * <p>两个 {@code sync} 的返回类型相同（都是契约的 {@link BlogSyncResult}），
     * 所以编译期那道保护<b>只能</b>来自参数表。如果哪天参数表变得相同、而保护"看起来还在"，
     * 那只可能是返回值被人改成了不同的类型 —— 那种保护是偶然的，本测试要在这里拦住它。</p>
     */
    @Test
    void syncDiffersInItsParameterTableWhichIsTheOnlyCompileTimeProtection() {
        Method csdn = methodNamed(CsdnBlogSyncPort.class, "sync");
        Method juejin = methodNamed(JuejinBlogSyncPort.class, "sync");

        assertNotEquals(ContractShape.signature(csdn), ContractShape.signature(juejin),
                "两个 sync 变得逐字相同了 —— 编译期那道保护消失，这个方法也成了静默换位点。"
                        + "要么把参数表恢复成不同的形状（掘金确实多收分类与标签 id），"
                        + "要么接受它、把它加进 INTERCHANGEABLE 并补不对称桩。");
        assertSame(BlogSyncResult.class, csdn.getReturnType(),
                "两个 sync 的返回类型必须相同（契约的 BlogSyncResult）—— 编译期保护只能来自参数表，"
                        + "不能来自「返回值恰好不同」这个偶然。");
        assertSame(csdn.getReturnType(), juejin.getReturnType(),
                "两个 sync 的返回类型应当相同。若它们不同，「接收者写反编译不过」这件事就同时有"
                        + "两个来源，本测试的结论要重写。");
    }

    /** 端口上名字叫 {@code name} 的方法必须**恰好一个**：重载会让下面的断言静默检查另一个方法。 */
    private static Method methodNamed(Class<?> port, String name) {
        Method found = null;
        for (Method method : port.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) continue;
            if (!name.equals(method.getName())) continue;
            assertNull(found,
                    port.getSimpleName() + " 上有多个名为 " + name + " 的方法（重载）。"
                            + "本测试按名字取方法，重载会让它静默检查另一个 —— 先明确要钉哪一个。");
            found = method;
        }
        if (found == null) {
            throw new AssertionError(port.getSimpleName() + " 上没有名为 " + name + " 的方法。");
        }
        return found;
    }

    private static Set<String> interchangeableMethods() {
        Map<String, String> csdn = signaturesByName(CsdnBlogSyncPort.class);
        Map<String, String> juejin = signaturesByName(JuejinBlogSyncPort.class);
        Set<String> result = new TreeSet<>();
        for (Map.Entry<String, String> entry : csdn.entrySet()) {
            if (entry.getValue().equals(juejin.get(entry.getKey()))) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private static Map<String, String> signaturesByName(Class<?> port) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Method method : port.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) continue;
            byName.put(method.getName(), ContractShape.signature(method));
        }
        return byName;
    }
}
