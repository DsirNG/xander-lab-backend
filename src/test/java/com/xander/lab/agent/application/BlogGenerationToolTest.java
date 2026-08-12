package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlogGenerationToolTest {

    @Test
    void forwardsStructuredStagesAndToolOwnedDeltas() {
        BlogAgentService blogAgentService = mock(BlogAgentService.class);
        BlogAgentTask created = task(9L, null, null);
        BlogAgentTask completed = task(9L, "生成标题", "生成摘要");
        BlogAgentTaskVO completedView = new BlogAgentTaskVO();
        completedView.setTask(completed);
        when(blogAgentService.create(eq(42L), any())).thenReturn(created);
        when(blogAgentService.runStream(eq(9L), eq(42L), any(), any())).thenAnswer(invocation -> {
            BiConsumer<String, String> events = invocation.getArgument(2);
            BooleanSupplier cancellation = invocation.getArgument(3);
            assertThat(cancellation.getAsBoolean()).isFalse();
            events.accept("stage", "write|正在撰写文章");
            events.accept("delta", "{\"title\":\"增量");
            events.accept("stage", "review|正在审校");
            return completedView;
        });

        BlogGenerationTool tool = new BlogGenerationTool(blogAgentService);
        RecordingProgress progress = new RecordingProgress();
        JsonNode result = tool.execute(42L,
                JsonNodeFactory.instance.objectNode().put("input", "写一篇流式博客"), progress);

        assertThat(progress.progress).contains(
                "write|开始写作正文…",
                "review|正在做发布前审校…");
        assertThat(progress.deltas).containsExactly("{\"title\":\"增量");
        assertThat(result.path("taskId").asLong()).isEqualTo(9L);
        assertThat(result.path("title").asText()).isEqualTo("生成标题");
    }

    private BlogAgentTask task(Long id, String title, String summary) {
        BlogAgentTask task = new BlogAgentTask();
        task.setId(id);
        task.setTitle(title);
        task.setSummary(summary);
        return task;
    }

    private static final class RecordingProgress implements AgentTool.ProgressListener {
        private final List<String> progress = new ArrayList<>();
        private final List<String> deltas = new ArrayList<>();

        @Override
        public void onProgress(String message) {
            progress.add(message);
        }

        @Override
        public void onToolDelta(String delta) {
            deltas.add(delta);
        }
    }
}
