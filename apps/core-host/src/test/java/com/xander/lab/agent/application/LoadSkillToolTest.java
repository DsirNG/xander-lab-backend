package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentUserSkill;
import com.xander.lab.agent.infrastructure.AgentUserSkillMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 分级披露的下半段：模型自己决定读哪份正文，读不到时得到能自我纠正的错误。 */
class LoadSkillToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> progress = new ArrayList<>();

    @Test
    void loadingABuiltinReturnsTheWholeBodyAndItsAdvisoryToolNames() {
        LoadSkillTool tool = new LoadSkillTool(catalog(List.of(new BuiltinAgentSkills().blogWritingSkill())));

        JsonNode result = tool.execute(42L, args("blog-writing"), progress::add);

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("name").asText()).isEqualTo("blog-writing");
        assertThat(result.path("source").asText()).isEqualTo("builtin");
        assertThat(result.path("instructions").asText()).contains("当前是内容创作任务");
        // toolNames 只是"这类活通常用这些工具"的提示；工具表本身与技能无关，每轮都是完整的。
        assertThat(result.path("toolNames")).isNotEmpty();
        assertThat(progress).containsExactly("正在按 blog-writing 的做法准备");
    }

    @Test
    void loadingAUserSkillReportsItsOwnVersionAndSource() {
        LoadSkillTool tool = new LoadSkillTool(catalog(List.of(),
                userSkill("weekly-review", 3, "每周复盘时读它", "先看上周计划，再看实际完成")));

        JsonNode result = tool.execute(42L, args("weekly-review"), progress::add);

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("version").asText()).isEqualTo("3");
        assertThat(result.path("source").asText()).isEqualTo("user");
        assertThat(result.path("instructions").asText()).isEqualTo("先看上周计划，再看实际完成");
    }

    /**
     * 用户自建的正文要带着自己的边界一起进上下文。
     *
     * <p>它是用户可控文本，会连着这条结果长期留在对话记录里；边界只写在提示词里不够——
     * 那句话可能早就被压缩换掉了，而正文还在。审批本身拦得住外部副作用
     * （见 {@link AgentToolExecutionPipeline}），这条说明防的是模型被正文说服去绕过它。</p>
     */
    @Test
    void aUserAuthoredBodyCarriesItsOwnBoundaryAndABuiltinDoesNot() {
        LoadSkillTool userTool = new LoadSkillTool(catalog(List.of(),
                userSkill("weekly-review", 1, "每周复盘时读它", "先看上周计划")));
        LoadSkillTool builtinTool = new LoadSkillTool(catalog(List.of(new BuiltinAgentSkills().blogWritingSkill())));

        assertThat(userTool.execute(42L, args("weekly-review"), progress::add).path("notice").asText())
                .contains("用户自建").contains("不能放宽审批策略");
        assertThat(builtinTool.execute(42L, args("blog-writing"), progress::add).has("notice")).isFalse();
    }

    /** 错误里必须点回目录，否则模型只会换一个同样凭印象猜的名字再试一次。 */
    @Test
    void anUnknownNamePointsBackAtTheCatalogInsteadOfFailingSilently() {
        LoadSkillTool tool = new LoadSkillTool(catalog(List.of(new BuiltinAgentSkills().blogWritingSkill())));

        JsonNode result = tool.execute(42L, args("sse-code-delivery"), progress::add);

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asText())
                .contains("没有名为 sse-code-delivery 的可用技能")
                .contains(AgentSkillCatalog.CATALOG_OPEN_TAG)
                .contains("不要凭印象猜名字");
        assertThat(progress).isEmpty();
    }

    @Test
    void aMalformedNameIsRejectedBeforeAnyLookup() {
        AgentUserSkillMapper mapper = mock(AgentUserSkillMapper.class);
        LoadSkillTool tool = new LoadSkillTool(new AgentSkillCatalog(List.of(), mapper, objectMapper));

        assertThat(tool.execute(42L, args("Blog Writing"), progress::add).path("error").asText())
                .contains("技能名不合法");
        assertThat(tool.execute(42L, args(""), progress::add).path("ok").asBoolean()).isFalse();
        assertThat(tool.execute(42L, null, progress::add).path("ok").asBoolean()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    /**
     * 目录是做法规则，不是能力清单，而且读一份技能不能有副作用。
     *
     * <p>后者决定了它不需要审批：审批是真正的安全边界（见 {@link AgentToolExecutionPipeline}），
     * 一件只读工具走审批只会让每次读规则都卡住一次用户。</p>
     */
    @Test
    void theToolTellsTheModelTheCatalogIsAboutHowNotAboutWhat() {
        LoadSkillTool tool = new LoadSkillTool(AgentSkillCatalog.empty());

        assertThat(tool.approvalReason(args("blog-writing"))).isEmpty();
        assertThat(tool.description()).contains("只读，不产生任何副作用");
        assertThat(tool.promptSection()).get()
                .extracting(AgentPromptSection::order).isEqualTo(AgentPromptSection.ORDER_WORKFLOW);
        assertThat(tool.promptSection().orElseThrow().text())
                .contains("不是能力清单")
                .contains("普通问答、闲聊、一次就能答完的问题不要读")
                .contains("同一个名字不要读第二遍")
                // 内置正文可以细化本准则，用户自建正文不能反过来放宽它——否则改一份自建技能
                // 就能给自己写一句"发布不用审批"。
                .contains("内置技能的正文与本准则冲突时以正文为准")
                .contains("不能放宽审批");
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

    private JsonNode args(String name) {
        return JsonNodeFactory.instance.objectNode().put("name", name);
    }
}
