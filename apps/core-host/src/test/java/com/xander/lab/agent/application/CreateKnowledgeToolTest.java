package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.recitation.spi.MaterialDraft;
import com.xander.lab.recitation.spi.MaterialView;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 这个测试本身就是"工具只需要端口"的证明：mock 的是 {@link RecitationMaterialPort}，
 * 工具若能编译并通过，就说明它对 recitation 的实现类没有任何依赖。
 */
class CreateKnowledgeToolTest {

    @Test
    void createsKnowledgeForTheExplicitConversationUser() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        MaterialView created = new MaterialView(
                8L, "勾股定理", "直角三角形两直角边平方和等于斜边平方",
                "MATH", "PRACTICE", BigDecimal.ZERO, "NEW", 0, null);
        when(materialPort.createMaterial(eq(42L), argThat(draft ->
                "勾股定理".equals(draft.title())
                        && "MATH".equals(draft.knowledgeType())
                        && draft.testMode() == null))).thenReturn(created);

        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("title", "勾股定理")
                .put("content", "直角三角形两直角边平方和等于斜边平方")
                .put("knowledgeType", "MATH");
        JsonNode result = new CreateKnowledgeTool(materialPort)
                .execute(42L, args, new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("id").asLong()).isEqualTo(8L);
        assertThat(result.path("testMode").asText()).isEqualTo("PRACTICE");
        assertThat(result.path("knowledgeUrl").asText()).isEqualTo("/workspace/knowledge/8");
        // testMode 传的是 null（"按类型自动匹配"），不是空字符串 —— 这个区别会决定
        // 提供方是"自动匹配考法"还是"固定一种考法"，而且不传与传空在 JSON 上都看不出来。
        verify(materialPort).createMaterial(eq(42L), argThat(MaterialDraft.class::isInstance));
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
