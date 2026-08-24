package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.recitation.api.CreateRecitationMaterialRequest;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.application.RecitationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateKnowledgeToolTest {

    @Test
    void createsKnowledgeForTheExplicitConversationUser() {
        RecitationService service = mock(RecitationService.class);
        RecitationMaterialView created = new RecitationMaterialView(
                8L, "勾股定理", "直角三角形两直角边平方和等于斜边平方", 21,
                "MATH", "PRACTICE", BigDecimal.ZERO, "NEW", 0,
                null, null, LocalDateTime.now(), LocalDateTime.now());
        when(service.createMaterial(eq(42L), argThat(request ->
                "勾股定理".equals(request.title())
                        && "MATH".equals(request.knowledgeType())
                        && request.testMode() == null))).thenReturn(created);

        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("title", "勾股定理")
                .put("content", "直角三角形两直角边平方和等于斜边平方")
                .put("knowledgeType", "MATH");
        JsonNode result = new CreateKnowledgeTool(service)
                .execute(42L, args, new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("id").asLong()).isEqualTo(8L);
        assertThat(result.path("testMode").asText()).isEqualTo("PRACTICE");
        assertThat(result.path("knowledgeUrl").asText()).isEqualTo("/workspace/knowledge/8");
        verify(service).createMaterial(eq(42L), argThat(CreateRecitationMaterialRequest.class::isInstance));
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
