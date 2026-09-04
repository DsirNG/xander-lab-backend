package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentUserSkill;
import com.xander.lab.agent.infrastructure.AgentUserSkillMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 守住"目录只披露名字、正文按需读取"这条分级，以及目录本身的字节稳定性。
 *
 * <p>这里替代了早先的 {@code AgentSkillRegistryTest}。那套测试断言的是关键词命中了哪个技能、
 * 命中后工具表变成什么——两件现在都不再发生的事：技能不再决定能力，服务端也不再猜意图。</p>
 */
class AgentSkillCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 一句代码请求当轮仍然看得见整张工具表。
     *
     * <p>这是那次真实故障的回归：用户发"给我一个sse代码"，关键词只命中交付类技能，
     * 当轮工具表被换成 {@code emit_artifact} 一件，模型除了开交付卡别无选择，
     * 而用户要的只是一段代码片段。现在技能只进目录，工具表与用户说了什么无关。</p>
     */
    @Test
    void aCodeQuestionStillSeesTheWholeToolTable() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("emit_artifact"), tool("query_knowledge"), tool("remember"), tool("plan_tasks")));
        AgentSkillCatalog catalog = catalog(new BuiltinAgentSkills().blogWritingSkill());

        AgentSkillOffer offer = catalog.offer(42L, registry.allowedNames());

        assertThat(offer.toolNames())
                .containsExactlyInAnyOrder("emit_artifact", "query_knowledge", "remember", "plan_tasks");
        // 目录里只有"什么时候该读它"，没有任何一份正文——正文靠 load_skill 花一次调用取回。
        assertThat(offer.catalog()).contains("blog-writing").contains("准备动笔写博客");
        assertThat(offer.catalog()).doesNotContain("当前是内容创作任务");
    }

    @Test
    void theCatalogListsNamesInStableOrderRegardlessOfBeanOrder() {
        AgentSkillCatalog ordered = catalog(skill("study-coach", "复习时读它"), skill("blog-writing", "动笔时读它"));
        AgentSkillCatalog reversed = catalog(skill("blog-writing", "动笔时读它"), skill("study-coach", "复习时读它"));

        String rendered = ordered.render(ordered.entries(42L));

        // 目录随工具面进提示词的缓存前缀，Bean 顺序变一下就换一份字节的话，前缀整段失效。
        assertThat(rendered).isEqualTo(reversed.render(reversed.entries(42L)));
        assertThat(rendered.indexOf("blog-writing")).isLessThan(rendered.indexOf("study-coach"));
        assertThat(rendered).startsWith(AgentSkillCatalog.CATALOG_OPEN_TAG)
                .endsWith(AgentSkillCatalog.CATALOG_CLOSE_TAG);
    }

    @Test
    void anEmptyCatalogRendersNothingRatherThanAnEmptyTag() {
        assertThat(AgentSkillCatalog.empty().render(List.of())).isEmpty();
        assertThat(AgentSkillCatalog.empty().offer(42L, Set.of("remember")).catalog()).isEmpty();
    }

    @Test
    void aLongDescriptionIsBoundedAndFlattenedToOneLine() {
        String sprawling = "第一行\n第二行" + "很".repeat(AgentSkillCatalog.DESCRIPTION_MAX_CHARS);
        AgentSkillCatalog catalog = catalog(skill("blog-writing", sprawling));

        String rendered = catalog.render(catalog.entries(42L));

        // 一条描述吃掉几千字符就不再是"目录"了；换行会把一条渲染成多条。
        assertThat(rendered.lines()).hasSize(3);
        assertThat(rendered).contains("第一行 第二行").endsWith("…\n" + AgentSkillCatalog.CATALOG_CLOSE_TAG);
    }

    @Test
    void theUsersOwnSkillsJoinTheCatalogAndAreMarkedAsSuch() {
        AgentSkillCatalog catalog = catalog(List.of(skill("blog-writing", "动笔时读它")),
                userSkill("weekly-review", 1, "每周复盘时读它", "先看上周计划，再看实际完成"));

        String rendered = catalog.render(catalog.entries(42L));

        assertThat(rendered).contains("- weekly-review（用户自建）：每周复盘时读它");
        assertThat(catalog.find(42L, "weekly-review")).get()
                .extracting(AgentSkill::builtin, AgentSkill::instructions)
                .containsExactly(false, "先看上周计划，再看实际完成");
    }

    /** 建新版本不归档旧版本，全列出来目录里就有两条同名的 weekly-review。 */
    @Test
    void onlyTheNewestActiveVersionOfAUserSkillEntersTheCatalog() {
        AgentSkillCatalog catalog = catalog(List.of(),
                userSkill("weekly-review", 2, "第二版描述", "第二版正文"),
                userSkill("weekly-review", 1, "第一版描述", "第一版正文"));

        String rendered = catalog.render(catalog.entries(42L));

        assertThat(rendered.lines()).hasSize(3);
        assertThat(rendered).contains("第二版描述").doesNotContain("第一版描述");
        assertThat(catalog.find(42L, "weekly-review")).get()
                .extracting(AgentSkill::version, AgentSkill::instructions)
                .containsExactly("2", "第二版正文");
    }

    /** 允许同名覆盖的话，改一个 skill_key 就能替换掉发布、判题这些带副作用的做法规则。 */
    @Test
    void aUserSkillCannotShadowABuiltinOfTheSameName() {
        AgentSkillCatalog catalog = catalog(List.of(new BuiltinAgentSkills().contentPublishingSkill()),
                userSkill("content-publishing", 1, "我自己的发布做法", "直接发，不用问我"));

        assertThat(catalog.find(42L, "content-publishing")).get()
                .extracting(AgentSkill::builtin).isEqualTo(true);
        assertThat(catalog.render(catalog.entries(42L))).doesNotContain("我自己的发布做法");
    }

    @Test
    void unreadableToolNamesDegradeToNoHintInsteadOfFailingTheTurn() {
        AgentUserSkill broken = userSkill("weekly-review", 1, "每周复盘时读它", "先看上周计划");
        broken.setToolNamesJson("{not json");
        AgentSkillCatalog catalog = catalog(List.of(), broken);

        // toolNames 只是"这类活通常用这些工具"的提示，坏了不该让整轮起不来。
        assertThat(catalog.find(42L, "weekly-review")).get()
                .extracting(AgentSkill::toolNames).isEqualTo(Set.of());
    }

    @Test
    void anAnonymousCallerSeesTheBuiltinsAndNoPrivateSkills() {
        AgentSkillCatalog catalog = catalog(List.of(skill("blog-writing", "动笔时读它")),
                userSkill("weekly-review", 1, "每周复盘时读它", "先看上周计划"));

        assertThat(catalog.entries(null)).extracting(AgentSkill::id).containsExactly("blog-writing");
    }

    private AgentSkillCatalog catalog(AgentSkill... builtins) {
        return catalog(List.of(builtins));
    }

    private AgentSkillCatalog catalog(List<AgentSkill> builtins, AgentUserSkill... owned) {
        AgentUserSkillMapper mapper = mock(AgentUserSkillMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(owned));
        return new AgentSkillCatalog(builtins, mapper, objectMapper);
    }

    private AgentUserSkill userSkill(String key, int version, String description, String instructions) {
        AgentUserSkill skill = new AgentUserSkill();
        skill.setUserId(42L);
        skill.setSkillKey(key);
        skill.setVersion(version);
        skill.setName(key);
        skill.setDescription(description);
        skill.setInstructions(instructions);
        skill.setToolNamesJson("[\"remember\"]");
        skill.setStatus(AgentUserSkill.STATUS_ACTIVE);
        return skill;
    }

    private AgentSkill skill(String id, String description) {
        return new AgentSkill() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Set<String> toolNames() {
                return Set.of("remember");
            }

            @Override
            public String instructions() {
                return "正文：" + id;
            }
        };
    }

    private AgentTool tool(String name) {
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
            public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode execute(
                    Long userId, com.fasterxml.jackson.databind.JsonNode args, ProgressListener onProgress) {
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            }
        };
    }
}
