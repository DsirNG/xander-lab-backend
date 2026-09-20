package com.xander.lab.agent.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住"只读"这一个概念只有一份定义。
 *
 * <p>现在有两处地方各自表达了"这个工具只读"：{@link AgentTool#readOnly()}（决定它能不能进并发批次）
 * 和 {@link SubAgentRunner#READ_ONLY_TOOLS}（决定子智能体能不能用它）。两者是同一个判断的两种用途，
 * 却存在两个常量里——加了新的只读工具只改一处，就会出现"能并发但子智能体用不了"或更糟的
 * "子智能体能用、但其实会写数据"。</p>
 *
 * <p>这个测试把两边都钉死在同一个清单上：改任何一边而不同步，它就会红。
 * 它扫描 classpath 而不是列类名，是为了让"新增一个覆写了 readOnly() 的工具"这件事本身
 * 就必须经过一次有意识的决定，而不是悄悄溜过去。</p>
 */
class ReadOnlyToolContractTest {

    private static final Set<String> READ_ONLY_TOOL_CLASSES = Set.of(
            "QueryKnowledgeTool", "QueryPostsTool", "QueryPlansTool",
            "QueryEmailRemindersTool", "QueryUnsyncedPostsTool", "RecallMemoryTool");

    private static final Set<String> READ_ONLY_TOOL_NAMES = Set.of(
            "query_knowledge", "query_posts", "query_plans",
            "query_email_reminders", "query_unsynced_posts", "recall_memory");

    @Test
    void onlyTheDeclaredReadOnlyToolsOptIntoConcurrency() throws Exception {
        assertThat(toolsDeclaringReadOnly())
                .as("覆写 readOnly() 的工具必须正好是这六个；新增或删除都要同步更新本测试与 READ_ONLY_TOOLS")
                .containsExactlyInAnyOrderElementsOf(READ_ONLY_TOOL_CLASSES);
    }

    @Test
    void theSubAgentAllowListMatchesTheSameSixTools() {
        assertThat(SubAgentRunner.READ_ONLY_TOOLS)
                .as("子智能体白名单必须与 readOnly() 的清单一致")
                .containsExactlyInAnyOrderElementsOf(READ_ONLY_TOOL_NAMES);
    }

    /**
     * 扫出所有**自己声明**了 {@code readOnly()} 的工具类。
     *
     * <p>用 {@code getDeclaredMethods} 而不是 {@code findMethod}：后者会把接口上的默认实现也算进来，
     * 那样每个工具都"声明"了，测试就恒真了。带 {@code $} 的类名是嵌套类（测试里的桩），排除掉。</p>
     */
    private Set<String> toolsDeclaringReadOnly() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AgentTool.class));
        Set<String> declaring = new LinkedHashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.xander.lab.agent.application")) {
            String className = candidate.getBeanClassName();
            if (className == null || className.contains("$")) continue;
            Class<?> type = Class.forName(className);
            for (Method method : type.getDeclaredMethods()) {
                if ("readOnly".equals(method.getName()) && method.getReturnType() == boolean.class) {
                    declaring.add(type.getSimpleName());
                }
            }
        }
        return declaring;
    }
}
