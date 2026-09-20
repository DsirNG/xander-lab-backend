package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.blogagent.spi.BlogAgentPort;
import com.xander.lab.blogagent.spi.BlogAgentTaskDraft;
import com.xander.lab.blogagent.spi.BlogAgentTaskView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本类 mock 的是<b>端口</b>而不是 {@code BlogAgentService} —— 这本身就是"工具只需要端口"的证明：
 * 如果哪天有人把工具改回依赖实现类，这个测试会编译不过。
 *
 * <p>⚠️ {@code 42L} 与 {@code 9L} 必须保持**互不相同**，因为端口是 {@code (userId, taskId)}
 * 而域侧是 {@code (taskId, userId)}。两个字面量一旦相同，换位写反也不会有任何断言变红。</p>
 */
class BlogGenerationToolTest {

    private static final Long USER_ID = 42L;
    private static final Long TASK_ID = 9L;

    @Test
    void forwardsStructuredStagesAndToolOwnedDeltas() {
        BlogAgentPort blogAgentPort = mock(BlogAgentPort.class);
        BlogAgentTaskView created = task(TASK_ID, null, null, null);
        BlogAgentTaskView completed = task(TASK_ID, "生成标题", "生成摘要", null);
        when(blogAgentPort.create(eq(USER_ID), any())).thenAnswer(invocation -> {
            BlogAgentTaskDraft draft = invocation.getArgument(1);
            assertThat(draft.clientRequestId()).isEqualTo("invocation-9");
            assertThat(draft.input()).isEqualTo("写一篇流式博客");
            return created;
        });
        // ⚠️ 参数顺序是 (userId, taskId)。写反会在这里的断言上响亮地失败，
        // 而不是静默把 42 当成任务 id 去查。
        when(blogAgentPort.runStream(eq(USER_ID), eq(TASK_ID), any(), any())).thenAnswer(invocation -> {
            BiConsumer<String, String> events = invocation.getArgument(2);
            BooleanSupplier cancellation = invocation.getArgument(3);
            assertThat(cancellation.getAsBoolean()).isFalse();
            events.accept("stage", "write|正在撰写文章");
            events.accept("delta", "{\"title\":\"增量");
            events.accept("stage", "review|正在审校");
            return completed;
        });

        BlogGenerationTool tool = new BlogGenerationTool(blogAgentPort);
        RecordingProgress progress = new RecordingProgress();
        JsonNode result = tool.execute(new AgentToolContext(USER_ID, 7L, 3L, "invocation-9"),
                JsonNodeFactory.instance.objectNode().put("input", "写一篇流式博客"), progress);

        assertThat(progress.progress).contains(
                "write|开始写作正文…",
                "review|正在做发布前审校…");
        assertThat(progress.deltas).containsExactly("{\"title\":\"增量");
        assertThat(result.path("taskId").asLong()).isEqualTo(TASK_ID);
        assertThat(result.path("title").asText()).isEqualTo("生成标题");
    }

    @Test
    void reconcilesOnlyAPersistedReadyTaskWithoutRestartingGeneration() {
        BlogAgentPort blogAgentPort = mock(BlogAgentPort.class);
        BlogAgentTaskView ready = task(TASK_ID, "生成标题", "生成摘要", null);
        when(blogAgentPort.findReadyTaskByClientRequestId(USER_ID, "invocation-9")).thenReturn(ready);
        BlogGenerationTool tool = new BlogGenerationTool(blogAgentPort);

        JsonNode result = tool.reconcile(new AgentToolContext(USER_ID, 7L, 3L, "invocation-9"),
                JsonNodeFactory.instance.objectNode().put("input", "写一篇博客"))
                .orElseThrow();

        assertThat(result.path("taskId").asLong()).isEqualTo(TASK_ID);
        // 重放只允许"认领"上次的产出，绝不允许重跑流水线（那是要花钱的）。
        verify(blogAgentPort, never()).runStream(any(), any(), any(), any());
    }

    @Test
    void leavesGenerationUnknownWhenNoReadyTaskIsPersisted() {
        BlogAgentPort blogAgentPort = mock(BlogAgentPort.class);
        BlogGenerationTool tool = new BlogGenerationTool(blogAgentPort);

        assertThat(tool.reconcile(new AgentToolContext(USER_ID, 7L, 3L, "invocation-9"),
                JsonNodeFactory.instance.objectNode().put("input", "写一篇博客"))).isEmpty();
        verify(blogAgentPort, never()).runStream(any(), any(), any(), any());
    }

    @Test
    void reportsPublishedPostIdAsMinusOneWhenTheTaskHasNotBeenPublished() {
        BlogAgentPort blogAgentPort = mock(BlogAgentPort.class);
        when(blogAgentPort.findReadyTaskByClientRequestId(USER_ID, "invocation-9"))
                .thenReturn(task(TASK_ID, "生成标题", "生成摘要", null));
        BlogGenerationTool tool = new BlogGenerationTool(blogAgentPort);

        JsonNode result = tool.reconcile(new AgentToolContext(USER_ID, 7L, 3L, "invocation-9"),
                JsonNodeFactory.instance.objectNode().put("input", "写一篇博客")).orElseThrow();

        // -1 而不是 null：下游把这个字段当数字读，null 会让它退化成 0，
        // 而 0 是一个合法的（不存在的）文章 id。
        assertThat(result.path("publishedPostId").asLong()).isEqualTo(-1L);
    }

    /** 端口视图只有 4 个分量；这个工厂把测试关心的那几个都显式写出来。 */
    private BlogAgentTaskView task(Long id, String title, String summary, Long publishedPostId) {
        return new BlogAgentTaskView(id, title, summary, publishedPostId);
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
