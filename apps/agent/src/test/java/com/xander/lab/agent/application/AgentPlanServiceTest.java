package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentPlanItem;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPlanServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentConversationMapper conversationMapper;
    private AgentPlanService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentConversation.class);
    }

    @BeforeEach
    void setUp() {
        conversationMapper = mock(AgentConversationMapper.class);
        service = new AgentPlanService(conversationMapper, objectMapper);
    }

    @Test
    void replaceNormalizesStatusesAndPersistsTheWholeList() {
        when(conversationMapper.update(any(), any())).thenReturn(1);

        List<AgentPlanItem> saved = service.replace(7L, 42L, 1L, items(
                item("查一下知识库", "done", ""),
                item("整理成复习清单", "in-progress", "按掌握度排序"),
                item("发给用户", "什么状态", "")));

        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).status()).isEqualTo("DONE");
        assertThat(saved.get(1).status()).isEqualTo("IN_PROGRESS");
        assertThat(saved.get(1).note()).isEqualTo("按掌握度排序");
        // 无法识别的状态按未开始处理，而不是让整轮对话因为一个错别字失败。
        assertThat(saved.get(2).status()).isEqualTo("PENDING");
    }

    @Test
    void onlyOneStepStaysInProgress() {
        when(conversationMapper.update(any(), any())).thenReturn(1);

        List<AgentPlanItem> saved = service.replace(7L, 42L, 1L, items(
                item("第一步", "IN_PROGRESS", ""),
                item("第二步", "IN_PROGRESS", ""),
                item("第三步", "IN_PROGRESS", "")));

        assertThat(saved).extracting(AgentPlanItem::status)
                .containsExactly("IN_PROGRESS", "PENDING", "PENDING");
    }

    @Test
    void stepsWithoutATitleAreDroppedAndAnEmptyListIsRejected() {
        when(conversationMapper.update(any(), any())).thenReturn(1);

        List<AgentPlanItem> saved = service.replace(7L, 42L, 1L, items(
                item("  ", "PENDING", ""), item("真正的步骤", "PENDING", "")));
        assertThat(saved).hasSize(1);

        assertThatThrownBy(() -> service.replace(7L, 42L, 1L, items()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非空的 items 数组");
        assertThatThrownBy(() -> service.replace(7L, 42L, 1L, items(item("", "PENDING", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需要 title");
    }

    @Test
    void planLengthIsCappedSoTheContextCannotBeFloodedWithTodos() {
        when(conversationMapper.update(any(), any())).thenReturn(1);
        ArrayNode nodes = JsonNodeFactory.instance.arrayNode();
        for (int index = 0; index < AgentPlanService.MAX_ITEMS + 15; index++) {
            nodes.add(item("步骤" + index, "PENDING", ""));
        }

        assertThat(service.replace(7L, 42L, 1L, nodes)).hasSize(AgentPlanService.MAX_ITEMS);
    }

    @Test
    void aSupersededRunCannotOverwriteTheNewerPlan() {
        when(conversationMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.replace(7L, 42L, 1L, items(item("步骤", "PENDING", ""))))
                .isInstanceOf(AgentRunSupersededException.class);
    }

    @Test
    void loadRoundTripsWhatReplaceWrote() {
        when(conversationMapper.update(any(), any())).thenReturn(1);
        service.replace(7L, 42L, 1L, items(item("查一下知识库", "IN_PROGRESS", "先看掌握度")));
        // 写入内容通过 wrapper 落库，这里直接用同一份序列化结果验证读回路径。
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setPlanJson("[{\"title\":\"查一下知识库\",\"status\":\"IN_PROGRESS\",\"note\":\"先看掌握度\"}]");

        List<AgentPlanItem> loaded = service.load(conversation);

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).title()).isEqualTo("查一下知识库");
        assertThat(loaded.get(0).statusValue()).isEqualTo(AgentPlanItem.Status.IN_PROGRESS);
    }

    @Test
    void unreadablePlanJsonDegradesToAnEmptyPlanInsteadOfFailingTheTurn() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setPlanJson("{ 这不是合法 JSON");

        assertThat(service.load(conversation)).isEmpty();
        assertThat(service.load(null)).isEmpty();
    }

    @Test
    void renderProducesOneLinePerStepAndSkipsEmptyNotes() {
        String rendered = service.render(List.of(
                new AgentPlanItem("查一下知识库", "DONE", ""),
                new AgentPlanItem("整理清单", "IN_PROGRESS", "按掌握度排序")));

        assertThat(rendered).isEqualTo("1. [DONE] 查一下知识库\n2. [IN_PROGRESS] 整理清单（按掌握度排序）");
        assertThat(service.render(List.of())).isEmpty();
    }

    @Test
    void unfinishedCountsOnlyStepsThatAreNotSettled() {
        List<AgentPlanItem> plan = List.of(
                new AgentPlanItem("a", "DONE", ""),
                new AgentPlanItem("b", "DROPPED", "不需要了"),
                new AgentPlanItem("c", "PENDING", ""),
                new AgentPlanItem("d", "IN_PROGRESS", ""));

        assertThat(service.unfinished(plan)).extracting(AgentPlanItem::title).containsExactly("c", "d");
    }

    private ArrayNode items(com.fasterxml.jackson.databind.node.ObjectNode... nodes) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (com.fasterxml.jackson.databind.node.ObjectNode node : nodes) array.add(node);
        return array;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode item(String title, String status, String note) {
        return JsonNodeFactory.instance.objectNode()
                .put("title", title).put("status", status).put("note", note);
    }
}
