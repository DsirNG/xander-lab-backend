package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRegistryTest {

    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(
            tool("query_posts"), tool("blog_generate"), tool("query_unsynced_posts"),
            tool("publish_post"), tool("schedule_plan"), tool("query_plans"), tool("update_plan"),
            tool("email_reminder"), tool("query_email_reminders"), tool("update_email_reminder"),
            tool("image_generate"), tool("create_knowledge"), tool("query_knowledge"),
            tool("plan_tasks"), tool("remember"), tool("recall_memory")));

    @Test
    void everyTurnSeesTheWholeToolTable() {
        // 关键词门禁已移除：能力边界不再由规则层代模型决定，否则匹配不上词表的诉求会静默失能。
        assertThat(registry.allowedNames()).containsExactlyInAnyOrderElementsOf(registry.names());
        assertThat(registry.allowedNames()).contains("plan_tasks", "remember", "recall_memory");
    }

    @Test
    void toolNamesAreRenderedInStableOrder() {
        // 顺序稳定，同一套工具每轮渲染出的前缀完全一致，便于上游做 prompt 缓存。
        assertThat(registry.names()).isSorted();
        assertThat(registry.describeTools()).isEqualTo(registry.describeTools());
        assertThat(registry.describeTools().indexOf("\"blog_generate\""))
                .isLessThan(registry.describeTools().indexOf("\"query_posts\""));
    }

    @Test
    void describedToolsCarryDescriptionAndSchema() {
        String described = registry.describeTools(Set.of("plan_tasks"));

        assertThat(described).contains("plan_tasks").contains("plan_tasks description");
        assertThat(described).doesNotContain("query_posts");
    }

    @Test
    void emptySelectionRendersNoSchemaBlock() {
        assertThat(registry.describeTools(Set.of())).isEmpty();
        assertThat(registry.describeTools(null)).isEmpty();
    }

    @Test
    void unknownToolNameResolvesToNull() {
        assertThat(registry.get("no_such_tool")).isNull();
        assertThat(registry.get("plan_tasks")).isNotNull();
    }

    @Test
    void theToolSurfaceCarriesThePoliciesOfTheToolsInTheTable() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("emit_quiz", AgentPromptSection.delivery("出题必须下发答题卡")),
                tool("publish_post", AgentPromptSection.sideEffect("发布不可撤销")),
                tool("query_posts")));

        String surface = registry.describeToolSurface(Set.of("emit_quiz", "query_posts"));

        assertThat(surface)
                .contains("\"emit_quiz\"")
                .contains("工具使用准则")
                .contains("T1. 出题必须下发答题卡");
        // 不在本轮工具表里的工具不能在提示词里被规定行为：那是为模型根本调不到的工具立规矩，
        // 而"给我一个 sse 代码也被开成交付卡"正是这样来的。
        assertThat(surface).doesNotContain("发布不可撤销");
    }

    @Test
    void policiesAreNumberedByCategoryThenToolName() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("publish_post", AgentPromptSection.sideEffect("站外发布不可撤销")),
                tool("grade_answer", AgentPromptSection.assessment("先判分再讲评")),
                tool("emit_quiz", AgentPromptSection.delivery("下发答题卡")),
                tool("emit_artifact", AgentPromptSection.delivery("默认写在代码围栏里"))));

        String surface = registry.describeToolSurface(new LinkedHashSet<>(
                List.of("publish_post", "grade_answer", "emit_quiz", "emit_artifact")));

        assertThat(surface)
                .contains("T1. 默认写在代码围栏里")
                .contains("T2. 下发答题卡")
                .contains("T3. 先判分再讲评")
                .contains("T4. 站外发布不可撤销");
        // 渲染只取决于工具集合本身，与集合的迭代顺序无关，提示词前缀才能进上游 prompt 缓存。
        assertThat(surface).isEqualTo(registry.describeToolSurface(new LinkedHashSet<>(
                List.of("emit_artifact", "emit_quiz", "grade_answer", "publish_post"))));
    }

    @Test
    void toolsWithoutAPolicyRenderSchemasOnly() {
        assertThat(registry.describeToolSurface(Set.of("plan_tasks")))
                .isEqualTo(registry.describeTools(Set.of("plan_tasks")))
                .doesNotContain("工具使用准则");
        assertThat(registry.describeToolSurface(Set.of())).isEmpty();
    }

    /**
     * 技能目录挂在工具面末尾：schema → 工具准则 → 目录。
     *
     * <p>它进的是提示词的缓存前缀而不是对话记录，所以压缩碰不到它，
     * 也就不需要"压缩之后重新把目录建立起来"那套补救。</p>
     */
    @Test
    void theSkillCatalogRidesTheToolSurfaceAfterTheToolPolicies() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("load_skill", AgentPromptSection.workflow("先读做法再动手")),
                tool("emit_artifact", AgentPromptSection.delivery("默认写在代码围栏里"))));
        String catalog = "<available_skills>\n- blog-writing：动笔时读它\n</available_skills>";

        String surface = registry.describeToolSurface(Set.of("load_skill", "emit_artifact"), catalog);

        assertThat(surface.indexOf("\"load_skill\"")).isLessThan(surface.indexOf("工具使用准则"));
        assertThat(surface.indexOf("T1. 先读做法再动手")).isLessThan(surface.indexOf("T2. 默认写在代码围栏里"));
        assertThat(surface.indexOf("工具使用准则")).isLessThan(surface.indexOf("<available_skills>"));
        assertThat(surface).endsWith(catalog);
        // 同一份 (工具集合, 目录) 必须渲染出逐字节相同的结果：压缩调用与主调用各算一次，
        // 只有两次一致，摘要请求才是主请求的真前缀。
        assertThat(surface).isEqualTo(registry.describeToolSurface(Set.of("emit_artifact", "load_skill"), catalog));
    }

    /** 没有一份技能时不留空标签，也不多出一个换行——那都会白白换掉整段前缀。 */
    @Test
    void anEmptyCatalogLeavesTheToolSurfaceUnchanged() {
        assertThat(registry.describeToolSurface(Set.of("plan_tasks"), ""))
                .isEqualTo(registry.describeToolSurface(Set.of("plan_tasks")));
        assertThat(registry.describeToolSurface(Set.of("plan_tasks"), null))
                .isEqualTo(registry.describeToolSurface(Set.of("plan_tasks")));
        assertThat(registry.describeToolSurface(Set.of(), "<available_skills>\n- x：y\n</available_skills>"))
                .isEmpty();
    }

    /**
     * 动态工具按归属收窄：平台级人人可见，用户自己配的只对自己可见。
     *
     * <p>这是「甲的远端服务器不能出现在乙的工具面里」的唯一实现点。收窄之后
     * {@link AgentToolExecutionPipeline} 拿这份集合比对工具名，越权调用就被挡住了。</p>
     */
    @Test
    void dynamicToolsJoinTheTableScopedToTheirOwner() {
        AgentToolRegistry scoped = new AgentToolRegistry(List.of(tool("remember")));
        // 动态来源的契约是「这个用户能看到什么」：平台级那条对谁都算上，个人那条只对本人算上。
        scoped.setDynamicToolSources(List.of(userId -> {
            List<AgentTool> visible = new ArrayList<>();
            visible.add(tool("mcp__platform__ping"));
            if (userId == null || userId == 7L) visible.add(tool("mcp__mine__do"));
            return visible;
        }));

        assertThat(scoped.allowedNames(7L))
                .contains("remember", "mcp__mine__do", "mcp__platform__ping");
        assertThat(scoped.allowedNames(9L))
                .contains("remember", "mcp__platform__ping")
                .doesNotContain("mcp__mine__do");
        assertThat(scoped.allowedNames()).contains("mcp__mine__do", "mcp__platform__ping");
    }

    /** 内置工具的名字是既有行为的一部分，被远端服务器顶掉会让老会话的能力无声改变。 */
    @Test
    void aDynamicToolCannotShadowABuiltinTool() {
        AgentToolRegistry scoped = new AgentToolRegistry(List.of(tool("publish_post")));
        scoped.setDynamicToolSources(List.of(userId -> List.of(tool("publish_post"))));

        assertThat(scoped.get("publish_post").description()).isEqualTo("publish_post description");
        assertThat(scoped.names()).containsExactly("publish_post");
    }

    /** 动态来源取不到工具不该让整轮对话失败：少几个工具，对话照常。 */
    @Test
    void aFailingDynamicSourceLeavesTheBuiltinTableIntact() {
        AgentToolRegistry scoped = new AgentToolRegistry(List.of(tool("remember")));
        scoped.setDynamicToolSources(List.of(userId -> {
            throw new IllegalStateException("远端不可达");
        }));

        assertThat(scoped.allowedNames(7L)).containsExactly("remember");
        assertThat(scoped.allowedNames()).containsExactly("remember");
    }

    /**
     * 合并结果被缓存，直到显式失效。
     *
     * <p>缓存不是优化而是正确性前提：同一轮里渲染工具面会被调用多次（主调用与压缩调用各一次），
     * 只有它们读到同一份 map，{@code describeToolSurface} 才可能逐字节一致。</p>
     */
    @Test
    void invalidateDropsTheCachedDynamicMerge() {
        AgentToolRegistry scoped = new AgentToolRegistry(List.of(tool("remember")));
        List<AgentTool> supplied = new ArrayList<>();
        scoped.setDynamicToolSources(List.of(userId -> List.copyOf(supplied)));

        assertThat(scoped.allowedNames(7L)).containsExactly("remember");

        supplied.add(tool("mcp__new__thing"));
        assertThat(scoped.allowedNames(7L)).containsExactly("remember");

        scoped.invalidate();
        // allowedNames 返回的是 Set，顺序不保证；要顺序得用 names(userId)。
        assertThat(scoped.allowedNames(7L)).containsExactlyInAnyOrder("mcp__new__thing", "remember");
        assertThat(scoped.names(7L)).containsExactly("mcp__new__thing", "remember");
    }

    /**
     * 来源的版本号一变，合并视图自己重建，不需要任何人来调 {@code invalidate()}。
     *
     * <p>这是"把反向调用换成轮询"的那一半：若 MCP 客户端（integration 域）在配置变更后
     * 反向调注册表的 {@code invalidate()}，那是跨部署单元的实现类依赖。现在提供方只推进自己的
     * 版本号，消费方每次读取时比对——控制流只剩一个方向。</p>
     *
     * <p>同时钉住"版本不动就不重建"：否则每轮都白重建，工具面不再逐字节稳定，上游 prompt
     * 缓存整段失效，而且看不出任何症状。</p>
     */
    @Test
    void aSourceVersionBumpRebuildsTheMergeWithoutAnExplicitInvalidate() {
        AtomicLong version = new AtomicLong();
        List<AgentTool> supplied = new ArrayList<>();
        AgentToolRegistry scoped = new AgentToolRegistry(List.of(tool("remember")));
        scoped.setDynamicToolSources(List.of(new AgentDynamicToolSource() {
            @Override
            public long version() {
                return version.get();
            }

            @Override
            public List<AgentTool> dynamicTools(Long userId) {
                return List.copyOf(supplied);
            }
        }));

        assertThat(scoped.allowedNames(7L)).containsExactly("remember");

        supplied.add(tool("mcp__new__thing"));
        assertThat(scoped.allowedNames(7L)).containsExactly("remember");

        version.incrementAndGet();
        assertThat(scoped.names(7L)).containsExactly("mcp__new__thing", "remember");
    }

    /**
     * <b>版本必须先读、工具必须后取。</b>
     *
     * <p>反过来的顺序会留下一个<b>永久性</b>的陈旧缓存：先取工具（旧）、再读版本（新），
     * 于是"新版本 + 旧工具"被存下来，而版本再也不会变，那份旧工具就再也刷新不掉了。
     * 这个 bug 没有任何别的症状——功能测试全绿，只有线上偶发地少一个工具，所以只能用调用
     * 顺序断言钉住。</p>
     */
    @Test
    void theVersionIsReadBeforeTheToolsSoAConcurrentChangeCannotBeCachedAsFresh() {
        List<String> calls = new ArrayList<>();
        AgentToolRegistry scoped = new AgentToolRegistry(List.of(tool("remember")));
        scoped.setDynamicToolSources(List.of(new AgentDynamicToolSource() {
            @Override
            public long version() {
                calls.add("version");
                return 1L;
            }

            @Override
            public List<AgentTool> dynamicTools(Long userId) {
                calls.add("tools");
                return List.of();
            }
        }));

        scoped.allowedNames(7L);
        assertThat(calls).containsExactly("version", "tools");

        // 缓存命中时只该少掉"取工具"那一步，版本仍然每次都读——不读就发现不了变更，
        // 而发现变更正是这个机制存在的全部理由。所以第二次读是 version 而非 tools。
        scoped.allowedNames(7L);
        assertThat(calls).containsExactly("version", "tools", "version");
    }

    /** 默认版本 0 表示"这个来源永不变化"，注册表就不该因为读版本而反复重建。 */
    @Test
    void aSourceThatKeepsTheDefaultVersionIsNeverRebuiltSpontaneously() {
        List<AgentTool> supplied = new ArrayList<>();
        AgentToolRegistry scoped = new AgentToolRegistry(List.of(tool("remember")));
        scoped.setDynamicToolSources(List.of(userId -> List.copyOf(supplied)));

        assertThat(scoped.allowedNames(7L)).containsExactly("remember");

        supplied.add(tool("mcp__new__thing"));
        // 版本恒为 0，所以即使来源内容变了也不重建——这正是加版本号之前的行为，
        // 由显式 invalidate() 负责（见 invalidateDropsTheCachedDynamicMerge）。
        assertThat(scoped.allowedNames(7L)).containsExactly("remember");
    }

    private AgentTool tool(String name) {
        return tool(name, null);
    }

    private AgentTool tool(String name, AgentPromptSection section) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " description";
            }

            @Override
            public Optional<AgentPromptSection> promptSection() {
                return Optional.ofNullable(section);
            }

            @Override
            public JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode();
            }
        };
    }
}
