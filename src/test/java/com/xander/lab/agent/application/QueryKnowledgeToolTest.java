package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.application.RecitationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryKnowledgeToolTest {

    private static final RecitationService.MaterialQuery MASTERED =
            new RecitationService.MaterialQuery(null, "MASTERED", false, 1);
    private static final RecitationService.MaterialQuery DUE =
            new RecitationService.MaterialQuery(null, null, true, 1);

    @Test
    void pushesSearchAndFiltersDownToTheQueryInsteadOfFilteringInMemory() {
        RecitationService service = mock(RecitationService.class);
        RecitationService.MaterialQuery filter =
                new RecitationService.MaterialQuery("定律", "RECALLING", true, 10);
        when(service.searchMaterials(42L, filter)).thenReturn(List.of(
                material(3L, "牛顿第二定律", "CONCEPT", "RECALLING", 68, LocalDateTime.now().minusDays(1))));
        when(service.countMaterials(42L, filter)).thenReturn(1L);
        when(service.countMaterials(42L, RecitationService.MaterialQuery.all())).thenReturn(3L);
        when(service.countMaterials(42L, MASTERED)).thenReturn(1L);
        when(service.countMaterials(42L, DUE)).thenReturn(2L);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("search", "定律")
                .put("masteryLevel", "recalling")
                .put("dueOnly", true)
                .put("limit", 10);

        JsonNode result = new QueryKnowledgeTool(service).execute(42L, args, new NoopProgress());

        assertThat(result.path("total").asInt()).isEqualTo(3);
        assertThat(result.path("mastered").asInt()).isEqualTo(1);
        assertThat(result.path("learning").asInt()).isEqualTo(2);
        assertThat(result.path("dueForReview").asInt()).isEqualTo(2);
        assertThat(result.path("matched").asInt()).isEqualTo(1);
        assertThat(result.path("matchedTotal").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("title").asText()).isEqualTo("牛顿第二定律");
        assertThat(result.path("items").get(0).path("knowledgeUrl").asText())
                .isEqualTo("/workspace/knowledge/3");
        assertThat(result.has("moreMatches")).isFalse();
        verify(service).searchMaterials(42L, filter);
    }

    @Test
    void ignoresAnUnknownMasteryLevelRatherThanQueryingForIt() {
        RecitationService service = mock(RecitationService.class);
        when(service.searchMaterials(eq(7L), any())).thenReturn(List.of());

        new QueryKnowledgeTool(service).execute(7L, JsonNodeFactory.instance.objectNode()
                .put("masteryLevel", "超级熟"), new NoopProgress());

        ArgumentCaptor<RecitationService.MaterialQuery> captor =
                ArgumentCaptor.forClass(RecitationService.MaterialQuery.class);
        verify(service).searchMaterials(eq(7L), captor.capture());
        assertThat(captor.getValue().masteryLevel()).isEmpty();
    }

    @Test
    void saysHowManyMatchesWereLeftOutSoOnePageIsNotMistakenForAllEvidence() {
        RecitationService service = mock(RecitationService.class);
        RecitationService.MaterialQuery filter =
                new RecitationService.MaterialQuery("定理", "", false, 10);
        when(service.searchMaterials(7L, filter)).thenReturn(List.of(
                material(1L, "勾股定理", "MATH", "MASTERED", 96, LocalDateTime.now().plusDays(3))));
        when(service.countMaterials(7L, filter)).thenReturn(24L);
        when(service.countMaterials(7L, RecitationService.MaterialQuery.all())).thenReturn(250L);

        JsonNode result = new QueryKnowledgeTool(service)
                .execute(7L, JsonNodeFactory.instance.objectNode().put("search", "定理"), new NoopProgress());

        assertThat(result.path("total").asInt()).isEqualTo(250);
        assertThat(result.path("matched").asInt()).isEqualTo(1);
        assertThat(result.path("matchedTotal").asInt()).isEqualTo(24);
        assertThat(result.path("moreMatches").asText()).contains("还有 23 条");
    }

    @Test
    void returnsTheStoredContentSoTheAgentCanGroundItsAnswer() {
        RecitationService service = mock(RecitationService.class);
        when(service.searchMaterials(eq(7L), any())).thenReturn(List.of(
                material(1L, "勾股定理", "MATH", "MASTERED", 96, LocalDateTime.now().plusDays(3))));
        when(service.countMaterials(eq(7L), any())).thenReturn(1L);

        JsonNode result = new QueryKnowledgeTool(service)
                .execute(7L, JsonNodeFactory.instance.objectNode(), new NoopProgress());

        JsonNode item = result.path("items").get(0);
        assertThat(item.path("contentExcerpt").asText()).isEqualTo("勾股定理的内容");
        assertThat(item.path("contentLength").asInt()).isEqualTo("勾股定理的内容".length());
        assertThat(item.path("truncated").asBoolean()).isFalse();
        assertThat(result.path("hint").asText()).contains("contentExcerpt");
    }
    // PLACEHOLDER_MORE_TESTS

    @Test
    void centresTheExcerptOnTheSearchHitAndFlagsTruncation() {
        RecitationService service = mock(RecitationService.class);
        String content = "开头".repeat(400) + "关键结论在这里" + "结尾".repeat(400);
        when(service.searchMaterials(eq(7L), any())).thenReturn(List.of(withContent(content)));
        when(service.countMaterials(eq(7L), any())).thenReturn(1L);
        JsonNode args = JsonNodeFactory.instance.objectNode().put("search", "关键结论");

        JsonNode item = new QueryKnowledgeTool(service)
                .execute(7L, args, new NoopProgress())
                .path("items").get(0);

        assertThat(item.path("truncated").asBoolean()).isTrue();
        assertThat(item.path("contentExcerpt").asText()).contains("关键结论在这里");
        assertThat(item.path("contentLength").asInt()).isEqualTo(content.length());
    }

    @Test
    void fullContentReturnsMoreTextAndNarrowsTheItemCount() {
        RecitationService service = mock(RecitationService.class);
        String content = "长正文".repeat(300);
        when(service.searchMaterials(eq(7L), any())).thenReturn(List.of(
                withContent(content), withContent(content), withContent(content)));
        when(service.countMaterials(eq(7L), any())).thenReturn(3L);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("fullContent", true)
                .put("limit", 10);

        JsonNode result = new QueryKnowledgeTool(service).execute(7L, args, new NoopProgress());

        ArgumentCaptor<RecitationService.MaterialQuery> captor =
                ArgumentCaptor.forClass(RecitationService.MaterialQuery.class);
        verify(service).searchMaterials(eq(7L), captor.capture());
        // 取全文时条数上限收窄到 3，否则一次就能把上下文塞满。
        assertThat(captor.getValue().limit()).isEqualTo(3);
        assertThat(result.path("matched").asInt()).isEqualTo(3);
        assertThat(result.path("items").get(0).path("contentExcerpt").asText()).isEqualTo(content);
        assertThat(result.path("items").get(0).path("truncated").asBoolean()).isFalse();
    }

    @Test
    void tellsTheAgentNotToInventKnowledgeWhenNothingMatches() {
        RecitationService service = mock(RecitationService.class);
        when(service.searchMaterials(eq(9L), any())).thenReturn(List.of());
        when(service.countMaterials(eq(9L), any())).thenReturn(0L);

        JsonNode result = new QueryKnowledgeTool(service)
                .execute(9L, JsonNodeFactory.instance.objectNode().put("search", "没记过的东西"),
                        new NoopProgress());

        assertThat(result.path("matched").asInt()).isZero();
        assertThat(result.path("items")).isEmpty();
        assertThat(result.path("hint").asText()).contains("不要虚构");
    }

    private RecitationMaterialView withContent(String content) {
        return new RecitationMaterialView(1L, "标题", content, content.length(), "CONCEPT", "AI_QA",
                BigDecimal.valueOf(60), "RECALLING", 2, LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1), LocalDateTime.now().minusDays(5), LocalDateTime.now());
    }

    private RecitationMaterialView material(Long id, String title, String type, String level,
                                             int score, LocalDateTime nextReviewAt) {
        return new RecitationMaterialView(id, title, title + "的内容", title.length(), type,
                "RECITATION".equals(type) ? "AUDIO_RECITATION" : "AI_QA",
                BigDecimal.valueOf(score), level, 2, LocalDateTime.now().minusDays(1),
                nextReviewAt, LocalDateTime.now().minusDays(5), LocalDateTime.now());
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
