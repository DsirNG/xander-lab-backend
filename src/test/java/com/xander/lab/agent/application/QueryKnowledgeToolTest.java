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
        verify(service).listMaterials(42L);
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
