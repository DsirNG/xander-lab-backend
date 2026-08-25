package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentMemory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RememberToolTest {

    private final AgentMemoryService memoryService = mock(AgentMemoryService.class);
    private final RememberTool tool = new RememberTool(memoryService);

    @Test
    void upsertIsTheDefaultActionAndRecordsTheSourceConversation() {
        when(memoryService.remember(eq(42L), eq("writing-style"), eq("PREFERENCE"), eq("偏好口语化"), eq(7L)))
                .thenReturn(memory("writing-style", "PREFERENCE"));
        ObjectNode args = JsonNodeFactory.instance.objectNode()
                .put("memoryKey", "writing-style")
                .put("kind", "PREFERENCE")
                .put("content", "偏好口语化");

        JsonNode result = tool.execute(new AgentToolContext(42L, 7L, 1L), args, new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("upsert");
        assertThat(result.path("memoryKey").asText()).isEqualTo("writing-style");
        assertThat(result.path("kind").asText()).isEqualTo("PREFERENCE");
        assertThat(result.path("hint").asText()).contains("不要重复写入");
        verify(memoryService).remember(42L, "writing-style", "PREFERENCE", "偏好口语化", 7L);
    }

    @Test
    void deleteReportsHonestlyWhenNothingMatchedTheKey() {
        when(memoryService.forget(42L, "gone")).thenReturn(false);
        ObjectNode args = JsonNodeFactory.instance.objectNode()
                .put("action", "DELETE")
                .put("memoryKey", "gone");

        JsonNode result = tool.execute(AgentToolContext.ofUser(42L), args, new NoopProgress());

        assertThat(result.path("action").asText()).isEqualTo("delete");
        assertThat(result.path("removed").asBoolean()).isFalse();
        assertThat(result.path("hint").asText()).contains("不要向用户声称删除成功");
    }

    @Test
    void deleteConfirmsARealRemoval() {
        when(memoryService.forget(42L, "stale")).thenReturn(true);
        ObjectNode args = JsonNodeFactory.instance.objectNode()
                .put("action", "delete").put("memoryKey", "stale");

        JsonNode result = tool.execute(AgentToolContext.ofUser(42L), args, new NoopProgress());

        assertThat(result.path("removed").asBoolean()).isTrue();
        assertThat(result.path("hint").asText()).isEqualTo("记忆已删除。");
    }

    @Test
    void theUserOnlyOverloadStillWorksWithoutAConversation() {
        when(memoryService.remember(eq(42L), eq("job"), eq(""), eq("后端工程师"),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(memory("job", "PROFILE"));
        ObjectNode args = JsonNodeFactory.instance.objectNode()
                .put("memoryKey", "job").put("content", "后端工程师");

        JsonNode result = tool.execute(42L, args, new NoopProgress());

        assertThat(result.path("memoryKey").asText()).isEqualTo("job");
    }

    @Test
    void schemaRequiresAKeyAndListsTheMemoryKinds() {
        JsonNode schema = tool.parametersSchema();

        assertThat(tool.name()).isEqualTo("remember");
        assertThat(schema.path("required").toString()).isEqualTo("[\"memoryKey\"]");
        assertThat(schema.path("properties").path("action").path("enum").toString())
                .isEqualTo("[\"upsert\",\"delete\"]");
        assertThat(schema.path("properties").path("kind").path("enum").toString())
                .isEqualTo("[\"PROFILE\",\"PREFERENCE\",\"FACT\",\"PROJECT\"]");
        assertThat(tool.description()).contains("不该记的例子");
    }

    private AgentMemory memory(String key, String kind) {
        AgentMemory memory = new AgentMemory();
        memory.setMemoryKey(key);
        memory.setKind(kind);
        return memory;
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
