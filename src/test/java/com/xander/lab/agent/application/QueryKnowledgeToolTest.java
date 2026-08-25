package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.application.RecitationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryKnowledgeToolTest {

    @Test
    void returnsMasteryOverviewAndFiltersDueKnowledge() {
        RecitationService service = mock(RecitationService.class);
        LocalDateTime now = LocalDateTime.now();
        when(service.listMaterials(42L)).thenReturn(List.of(
                material(1L, "赤壁赋", "RECITATION", "FAMILIAR", 45, now.minusHours(1)),
                material(2L, "勾股定理", "MATH", "MASTERED", 96, now.plusDays(20)),
                material(3L, "牛顿第二定律", "CONCEPT", "RECALLING", 68, now.minusDays(1))));
        when(service.countMaterials(42L)).thenReturn(3L);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("search", "定律")
                .put("dueOnly", true)
                .put("limit", 10);

        JsonNode result = new QueryKnowledgeTool(service)
                .execute(42L, args, new NoopProgress());

        assertThat(result.path("total").asInt()).isEqualTo(3);
        assertThat(result.path("mastered").asInt()).isEqualTo(1);
        assertThat(result.path("learning").asInt()).isEqualTo(2);
        assertThat(result.path("dueForReview").asInt()).isEqualTo(2);
        assertThat(result.path("matched").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("title").asText()).isEqualTo("牛顿第二定律");
        assertThat(result.path("items").get(0).path("knowledgeUrl").asText())
                .isEqualTo("/workspace/knowledge/3");
        assertThat(result.has("statsScope")).isFalse();
        verify(service).listMaterials(42L);
    }

    @Test
    void returnsTheStoredContentSoTheAgentCanGroundItsAnswer() {
        RecitationService service = mock(RecitationService.class);
        when(service.listMaterials(7L)).thenReturn(List.of(
                material(1L, "勾股定理", "MATH", "MASTERED", 96, LocalDateTime.now().plusDays(3))));
        when(service.countMaterials(7L)).thenReturn(1L);

        JsonNode result = new QueryKnowledgeTool(service)
                .execute(7L, JsonNodeFactory.instance.objectNode(), new NoopProgress());

        JsonNode item = result.path("items").get(0);
        assertThat(item.path("contentExcerpt").asText()).isEqualTo("勾股定理的内容");
        assertThat(item.path("contentLength").asInt()).isEqualTo("勾股定理的内容".length());
        assertThat(item.path("truncated").asBoolean()).isFalse();
        assertThat(result.path("hint").asText()).contains("contentExcerpt");
    }

    @Test
    void centresTheExcerptOnTheSearchHitAndFlagsTruncation() {
        RecitationService service = mock(RecitationService.class);
        String content = "开头".repeat(400) + "关键结论在这里" + "结尾".repeat(400);
        when(service.listMaterials(7L)).thenReturn(List.of(withContent(content)));
        when(service.countMaterials(7L)).thenReturn(1L);
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
        when(service.listMaterials(7L)).thenReturn(List.of(
                withContent(content), withContent(content), withContent(content), withContent(content)));
        when(service.countMaterials(7L)).thenReturn(4L);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("fullContent", true)
                .put("limit", 10);

        JsonNode result = new QueryKnowledgeTool(service)
                .execute(7L, args, new NoopProgress());

        assertThat(result.path("matched").asInt()).isEqualTo(3);
        assertThat(result.path("items").get(0).path("contentExcerpt").asText()).isEqualTo(content);
        assertThat(result.path("items").get(0).path("truncated").asBoolean()).isFalse();
    }

    @Test
    void explainsThatCountsComeFromTheRecentBatchWhenTheLibraryIsLarger() {
        RecitationService service = mock(RecitationService.class);
        when(service.listMaterials(7L)).thenReturn(List.of(
                material(1L, "勾股定理", "MATH", "MASTERED", 96, LocalDateTime.now().plusDays(3))));
        when(service.countMaterials(7L)).thenReturn(250L);

        JsonNode result = new QueryKnowledgeTool(service)
                .execute(7L, JsonNodeFactory.instance.objectNode(), new NoopProgress());

        assertThat(result.path("total").asInt()).isEqualTo(250);
        assertThat(result.path("statsScope").asText())
                .contains("仅统计最近 1 条知识")
                .contains("total 是全部条数");
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
