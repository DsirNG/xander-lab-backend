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
 * <p>“同形不同源”是一种静默换位点，两个同步端口的
 * {@code findSucceeded(Long, Long) -> BlogSyncResult} 方法逐字相同，
 * 把 CSDN 接收者写成掘金仍然可以编译。本测试必须和被守卫的
 * {@code PublishPostTool} 位于同一个 deployment module，才能扫描到真实类路径。</p>
 *
 * <p>契约侧的 {@code BlogSyncPortInterchangeabilityTest} 钉的是“哪两个方法同形”；
 * 本测试钉的是“有没有人已经站到那个坑前面”。两者缺一不可。</p>
 */
class DualSyncPortConsumerTest {

    /**
     * 允许同时持有两个同步端口的类，且每个都必须有自己的不对称桩测试。
     *
     * <p>这份清单的意义是精确指出哪个类已经写好双端口行为测试，
     * 不是为了压过测试结果随意补条目。</p>
     */
    private static final Set<String> DUAL_PORT_CONSUMERS = Set.of(
            // 不对称桩在 PublishPostToolTest 的两个 reconcile 用例里。
            "com.xander.lab.agent.application.PublishPostTool");

    @Test
    void noClassHoldsBothSyncPortsUnlessItIsDeclaredAndHasAsymmetricStubs() {
        Set<String> found = classesHoldingBothPorts();
        assertEquals(new TreeSet<>(DUAL_PORT_CONSUMERS), found,
                "类路径上出现了同时持有 CsdnBlogSyncPort 与 JuejinBlogSyncPort 的类。"
                        + "请先为该类补不对称桩行为测试，再登记到 DUAL_PORT_CONSUMERS。"
                        + "如果清单中的类已不再同时持有两个端口，则删除过期条目。");
    }

    @Test
    void everyDeclaredConsumerReallyDoesHoldBothPorts() {
        Set<String> actual = classesHoldingBothPorts();
        for (String declared : DUAL_PORT_CONSUMERS) {
            assertTrue(actual.contains(declared),
                    declared + " 在 DUAL_PORT_CONSUMERS 里，但它并没有同时持有两个同步端口");
        }
    }

    /** 扫描 Agent deployment module 的类路径，找出同时持有两个同步端口的具体类。 */
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
                "有类加载不了，本测试因此无法证明它们没有同时持有两个同步端口：\n  "
                        + String.join("\n  ", unloadable));
        return found;
    }

    private static boolean dependsOnBothPorts(Class<?> type) {
        Set<Class<?>> dependencies = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            dependencies.add(field.getType());
        }
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
