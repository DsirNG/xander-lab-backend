package com.xander.lab.migration;

import com.xander.lab.integration.api.CsdnBlogSyncPort;
import com.xander.lab.integration.api.JuejinBlogSyncPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类路径上不许<b>悄悄</b>出现同时持有两个站外同步端口的类。
 *
 * <h2>这条守卫补的是哪一步</h2>
 * <p>第十九刀发现了"同形不同源"这一种静默换位点（{@code notes/contract-extraction.md}），
 * 并在两个凭证端口上配了不对称桩。但第十六刀那两个<b>同步</b>端口的同名方法
 * （{@code sync} 与 {@code findSucceeded}）里，{@code findSucceeded(Long, Long) -> BlogSyncResult}
 * 在两个端口上<b>逐字相同</b>：把 {@code csdnPort.findSucceeded(u, p)} 写成
 * {@code juejinPort.findSucceeded(u, p)} <b>编译通过</b>（{@code sync} 的参数表不同，
 * 所以那个方法上写反编译不过 —— 那道保护是"参数表恰好不同"的副产品，不是设计出来的）。</p>
 *
 * <p>⚠️ 这条守卫<b>写出来当天就抓到一个</b>：{@code PublishPostTool} 早就同时持有两个端口，
 * 而且 {@code reconcile} 里那两行 {@code findSucceeded(context.userId(), local.id())}
 * <b>相邻、参数表逐字相同、只是接收者不同</b> —— 正是本测试要拦的形状。
 * ADR 待办 54 原文写的是"今天没有调用点会写反只是巧合 / 暂时没有写反的机会"，
 * <b>那句是错的</b>：写反的机会一直存在，只是从来没有东西会报错。
 * 这正是"先写判据、再用判据去测一遍自己相信的话"的价值 ——
 * 直接量出来的东西推翻了纸面上的结论。</p>
 *
 * <p>契约侧的 {@code BlogSyncPortInterchangeabilityTest} 钉的是"哪两个方法同形"；
 * 本测试钉的是"有没有人已经站到那个坑前面"。两者缺一不可：
 * 前者说得清坑在哪，后者说得出现在有没有人正踩着它。</p>
 *
 * <h2>变红了怎么办</h2>
 * <p>先给那个类写不对称桩的行为测试，再把它加进 {@link #DUAL_PORT_CONSUMERS}：</p>
 * <ul>
 *   <li>两个端口的桩必须取<b>不同的值</b>（例如 CSDN 桩返回一个结果、掘金桩返回 {@code null}；
 *       另一个方向再来一条）。两个桩取同一个值时，接收者写反<b>照样绿</b> ——
 *       那不是"断言写少了"，是"断言选的值分不开两个被测对象"。</li>
 *   <li>不对称桩要落在这个类<b>自己的测试</b>里：那是唯一同时看得见两个端口的地方。</li>
 *   <li>除了值，还要断言<b>另一个端口零交互</b>（{@code verify(other, never())}）：
 *       写反时先红的是那一条，失败信息直接说"这个 mock 从未被调用"，
 *       而不是抛一个看着像环境故障的 {@code NoSuchElementException}。</li>
 * </ul>
 */
class DualSyncPortConsumerTest {

    /**
     * 允许同时持有两个同步端口的类，且每个都必须有自己的不对称桩测试。
     *
     * <p>用<b>全限定名</b>而不是简单名：简单名在两个包下同名时会撞在一起，
     * 而这份清单的意义恰恰是"精确指出哪一个类"。</p>
     *
     * <p>⚠️ 这是本测试的<b>基线</b>：加条目意味着"这个类的不对称桩已经写好了"，
     * 不是"这个类确实需要两个端口"。不要为了让它变绿往回填。</p>
     */
    private static final Set<String> DUAL_PORT_CONSUMERS = Set.of(
            // 唯一同时持有两个站外同步端口的类。它的不对称桩在
            // com.xander.lab.agent.application.PublishPostToolTest
            // 的 reconcileOnCsdnReadsTheCsdnPortAndNeverTheJuejinOne /
            // reconcileOnJuejinReadsTheJuejinPortAndNeverTheCsdnOne 两条里。
            "com.xander.lab.agent.application.PublishPostTool");

    @Test
    void noClassHoldsBothSyncPortsUnlessItIsDeclaredAndHasAsymmetricStubs() {
        Set<String> found = classesHoldingBothPorts();
        assertEquals(new TreeSet<>(DUAL_PORT_CONSUMERS), found,
                "类路径上出现了同时持有 CsdnBlogSyncPort 与 JuejinBlogSyncPort 的类。\n"
                        + "⚠️ 这两个端口的 findSucceeded(Long, Long) -> BlogSyncResult 逐字相同，"
                        + "把接收者写反会**编译通过**、耦合指标一动不动、26 条门禁全绿。\n"
                        + "动作：①先给这个类写不对称桩的行为测试（两个桩取不同的值，两个方向各一条）；"
                        + "②再把它加进 DUAL_PORT_CONSUMERS。\n"
                        + "多出来的（没声明就出现的）在上面的 diff 里；少掉的说明清单里那条已经不需要了，"
                        + "删掉它、也删掉它那条不对称桩测试。");
    }

    /**
     * 反向 sanity：清单里的条目必须**真的**同时持有两个端口。
     *
     * <p>没有这条，清单可以留下一个已经不存在的类名而上面那条断言照样绿 ——
     * 判据只朝一个方向看。这与门禁里 {@code shared-kernel-classification} 缺反向判据、
     * {@code util} 变成死条目是同一类错误。</p>
     */
    @Test
    void everyDeclaredConsumerReallyDoesHoldBothPorts() {
        Set<String> actual = classesHoldingBothPorts();
        for (String declared : DUAL_PORT_CONSUMERS) {
            assertTrue(actual.contains(declared),
                    declared + " 在 DUAL_PORT_CONSUMERS 里，但它并没有同时持有两个同步端口 —— "
                            + "清单过期了，删掉它。");
        }
    }

    /** 扫描 {@code com.xander.lab} 下所有具体类，挑出字段或构造参数里同时出现两个端口的那些。 */
    private static Set<String> classesHoldingBothPorts() {
        Set<String> found = new TreeSet<>();
        List<String> unloadable = new ArrayList<>();
        for (BeanDefinition definition : scanConcreteClasses()) {
            String className = definition.getBeanClassName();
            if (className == null) continue;
            Class<?> type;
            try {
                type = Class.forName(className, false, DualSyncPortConsumerTest.class.getClassLoader());
            } catch (Throwable failure) {
                unloadable.add(className + " (" + failure + ")");
                continue;
            }
            if (dependsOnBothPorts(type)) {
                found.add(type.getName());
            }
        }
        assertTrue(unloadable.isEmpty(),
                "有类加载不了，本测试因此无法证明它们没有同时持有两个同步端口。\n"
                        + "不要静默跳过 —— 那会让这条守卫只朝一个方向看。\n  "
                        + String.join("\n  ", unloadable));
        return found;
    }

    private static boolean dependsOnBothPorts(Class<?> type) {
        Set<Class<?>> dependencies = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            dependencies.add(field.getType());
        }
        // Lombok 的 @RequiredArgsConstructor 会生成一个全参构造 —— 构造参数是同一个事实的第二个来源。
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            dependencies.addAll(Arrays.asList(constructor.getParameterTypes()));
        }
        return dependencies.contains(CsdnBlogSyncPort.class)
                && dependencies.contains(JuejinBlogSyncPort.class);
    }

    private static Set<BeanDefinition> scanConcreteClasses() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter((metadataReader, factory) ->
                metadataReader.getClassMetadata().getClassName().startsWith("com.xander.lab.")
                        && metadataReader.getClassMetadata().isConcrete());
        return new LinkedHashSet<>(provider.findCandidateComponents("com.xander.lab"));
    }
}
