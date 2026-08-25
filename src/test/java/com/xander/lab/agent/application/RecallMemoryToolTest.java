package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentMemory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecallMemoryToolTest {

    private final AgentMemoryService memoryService = mock(AgentMemoryService.class);
    private final RecallMemoryTool tool = new RecallMemoryTool(memoryService);

    @Test
    void returnsTheMatchedMemoriesWithTheirKeysAndContent() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 25, 10, 30);
        when(memoryService.recall(42L, "写作", 3)).thenReturn(List.of(
                memory("writing-style", "PREFERENCE", "偏好口语化", updatedAt)));

        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode()
                .put("query", "写作").put("limit", 3), new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("matched").asInt()).isEqualTo(1);
        JsonNode item = result.path("items").get(0);
        assertThat(item.path("memoryKey").asText()).isEqualTo("writing-style");
        assertThat(item.path("kind").asText()).isEqualTo("PREFERENCE");
        assertThat(item.path("content").asText()).isEqualTo("偏好口语化");
        assertThat(item.path("updatedAt").asText()).isEqualTo(updatedAt.toString());
        assertThat(result.path("hint").asText()).contains("可直接用于回答");
    }

    @Test
    void defaultsToTheInjectionLimitAndAnEmptyQuery() {
        when(memoryService.recall(42L, "", AgentMemoryService.RECALL_LIMIT)).thenReturn(List.of());

        tool.execute(42L, JsonNodeFactory.instance.objectNode(), new NoopProgress());

        verify(memoryService).recall(42L, "", AgentMemoryService.RECALL_LIMIT);
    }

    @Test
    void anEmptyResultTellsTheAgentNotToInvent() {
        when(memoryService.recall(42L, "生日", AgentMemoryService.RECALL_LIMIT)).thenReturn(List.of());

        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode().put("query", "生日"),
                new NoopProgress());

        assertThat(result.path("matched").asInt()).isZero();
        assertThat(result.path("items")).isEmpty();
        assertThat(result.path("hint").asText()).contains("如实告知用户");
        assertThat(tool.description()).contains("不要凭印象编造");
    }

    @Test
    void aMemoryWithoutATimestampRendersAsAnEmptyString() {
        when(memoryService.recall(42L, "", AgentMemoryService.RECALL_LIMIT)).thenReturn(List.of(
                memory("job", "PROFILE", "后端工程师", null)));

        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode(), new NoopProgress());

        assertThat(result.path("items").get(0).path("updatedAt").asText()).isEmpty();
    }

    @Test
    void schemaDocumentsTheQueryAndDefaultLimit() {
        assertThat(tool.name()).isEqualTo("recall_memory");
        assertThat(tool.parametersSchema().path("properties").path("limit").path("description").asText())
                .contains(String.valueOf(AgentMemoryService.RECALL_LIMIT));
        assertThat(tool.description()).contains("已经自动注入");
    }

    private AgentMemory memory(String key, String kind, String content, LocalDateTime updatedAt) {
        AgentMemory memory = new AgentMemory();
        memory.setMemoryKey(key);
        memory.setKind(kind);
        memory.setContent(content);
        memory.setUpdatedAt(updatedAt);
        return memory;
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
