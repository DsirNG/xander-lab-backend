package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.recitation.spi.MaterialFilter;
import com.xander.lab.recitation.spi.MaterialView;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
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

/**
 * 这个测试本身就是"工具只需要端口"的证明：mock 的是 {@link RecitationMaterialPort}。
 *
 * <p><b>⚠️ 一条不变量搬走了。</b>这里原来有一个
 * {@code archivedKnowledgeStaysOutOfWhatTheAgentCanSee}：抓取工具构造的
 * {@code RecitationService.MaterialQuery}，断言它的 {@code archive()} 是 {@code ACTIVE}。
 * 契约筛选条件 {@link MaterialFilter} 只有四个分量、**不带归档维度**，所以工具连"把归档的
 * 也查出来"都表达不出来 —— 这个断言在工具这一层已经无处可写。不变量本身没有消失，
 * 只是整体挪到了提供方的换算上：{@code RecitationServiceTest} 的
 * {@code thePortSearchLandsOnTheSameActiveOnlyPredicateAsThePagesDefaultList}
 * 断言端口查询落到的 SQL 仍带 {@code archived_at IS NULL}。删掉旧断言不是放宽约束，
 * 是把约束挪到了它真正能被破坏的那一层。</p>
 */
class QueryKnowledgeToolTest {

    private static final MaterialFilter MASTERED = new MaterialFilter(null, "MASTERED", false, 1);
    private static final MaterialFilter DUE = new MaterialFilter(null, null, true, 1);

    @Test
    void pushesSearchAndFiltersDownToTheQueryInsteadOfFilteringInMemory() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        MaterialFilter filter = new MaterialFilter("定律", "RECALLING", true, 10);
        when(materialPort.searchMaterials(42L, filter)).thenReturn(List.of(
                material(3L, "牛顿第二定律", "CONCEPT", "RECALLING", 68, LocalDateTime.now().minusDays(1))));
        when(materialPort.countMaterials(42L, filter)).thenReturn(1L);
        when(materialPort.countMaterials(42L, MaterialFilter.all())).thenReturn(3L);
        when(materialPort.countMaterials(42L, MASTERED)).thenReturn(1L);
        when(materialPort.countMaterials(42L, DUE)).thenReturn(2L);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("search", "定律")
                .put("masteryLevel", "recalling")
                .put("dueOnly", true)
                .put("limit", 10);

        JsonNode result = new QueryKnowledgeTool(materialPort).execute(42L, args, new NoopProgress());

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
        verify(materialPort).searchMaterials(42L, filter);
    }

    @Test
    void theDefaultQueryCarriesNoFilterAtAllSoNothingWidensTheScope() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        when(materialPort.searchMaterials(eq(7L), any())).thenReturn(List.of());
        when(materialPort.countMaterials(eq(7L), any())).thenReturn(0L);

        new QueryKnowledgeTool(materialPort)
                .execute(7L, JsonNodeFactory.instance.objectNode(), new NoopProgress());

        // 默认口径必须是"什么都不筛、上限 10"。这条断言看着平淡，但它守住的是**作用域**：
        // 工具是唯一能替智能体决定"查哪些知识"的地方，默认值一旦被改宽（比如 dueOnly 变成
        // true、limit 变成 Integer.MAX_VALUE），智能体看到的证据集合就变了，而且不会有别的症状。
        ArgumentCaptor<MaterialFilter> captor = ArgumentCaptor.forClass(MaterialFilter.class);
        verify(materialPort).searchMaterials(eq(7L), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new MaterialFilter("", "", false, 10));
    }

    @Test
    void ignoresAnUnknownMasteryLevelRatherThanQueryingForIt() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        when(materialPort.searchMaterials(eq(7L), any())).thenReturn(List.of());

        new QueryKnowledgeTool(materialPort).execute(7L, JsonNodeFactory.instance.objectNode()
                .put("masteryLevel", "超级熟"), new NoopProgress());

        ArgumentCaptor<MaterialFilter> captor = ArgumentCaptor.forClass(MaterialFilter.class);
        verify(materialPort).searchMaterials(eq(7L), captor.capture());
        assertThat(captor.getValue().masteryLevel()).isEmpty();
    }

    @Test
    void saysHowManyMatchesWereLeftOutSoOnePageIsNotMistakenForAllEvidence() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        MaterialFilter filter = new MaterialFilter("定理", "", false, 10);
        when(materialPort.searchMaterials(7L, filter)).thenReturn(List.of(
                material(1L, "勾股定理", "MATH", "MASTERED", 96, LocalDateTime.now().plusDays(3))));
        when(materialPort.countMaterials(7L, filter)).thenReturn(24L);
        when(materialPort.countMaterials(7L, MaterialFilter.all())).thenReturn(250L);

        JsonNode result = new QueryKnowledgeTool(materialPort)
                .execute(7L, JsonNodeFactory.instance.objectNode().put("search", "定理"), new NoopProgress());

        assertThat(result.path("total").asInt()).isEqualTo(250);
        assertThat(result.path("matched").asInt()).isEqualTo(1);
        assertThat(result.path("matchedTotal").asInt()).isEqualTo(24);
        assertThat(result.path("moreMatches").asText()).contains("还有 23 条");
    }

    @Test
    void returnsTheStoredContentSoTheAgentCanGroundItsAnswer() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        when(materialPort.searchMaterials(eq(7L), any())).thenReturn(List.of(
                material(1L, "勾股定理", "MATH", "MASTERED", 96, LocalDateTime.now().plusDays(3))));
        when(materialPort.countMaterials(eq(7L), any())).thenReturn(1L);

        JsonNode result = new QueryKnowledgeTool(materialPort)
                .execute(7L, JsonNodeFactory.instance.objectNode(), new NoopProgress());

        JsonNode item = result.path("items").get(0);
        assertThat(item.path("contentExcerpt").asText()).isEqualTo("勾股定理的内容");
        assertThat(item.path("contentLength").asInt()).isEqualTo("勾股定理的内容".length());
        assertThat(item.path("truncated").asBoolean()).isFalse();
        assertThat(result.path("hint").asText()).contains("contentExcerpt");
    }

    @Test
    void centresTheExcerptOnTheSearchHitAndFlagsTruncation() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        String content = "开头".repeat(400) + "关键结论在这里" + "结尾".repeat(400);
        when(materialPort.searchMaterials(eq(7L), any())).thenReturn(List.of(withContent(content)));
        when(materialPort.countMaterials(eq(7L), any())).thenReturn(1L);
        JsonNode args = JsonNodeFactory.instance.objectNode().put("search", "关键结论");

        JsonNode item = new QueryKnowledgeTool(materialPort)
                .execute(7L, args, new NoopProgress())
                .path("items").get(0);

        assertThat(item.path("truncated").asBoolean()).isTrue();
        assertThat(item.path("contentExcerpt").asText()).contains("关键结论在这里");
        assertThat(item.path("contentLength").asInt()).isEqualTo(content.length());
    }

    @Test
    void fullContentReturnsMoreTextAndNarrowsTheItemCount() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        String content = "长正文".repeat(300);
        when(materialPort.searchMaterials(eq(7L), any())).thenReturn(List.of(
                withContent(content), withContent(content), withContent(content)));
        when(materialPort.countMaterials(eq(7L), any())).thenReturn(3L);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("fullContent", true)
                .put("limit", 10);

        JsonNode result = new QueryKnowledgeTool(materialPort).execute(7L, args, new NoopProgress());

        ArgumentCaptor<MaterialFilter> captor = ArgumentCaptor.forClass(MaterialFilter.class);
        verify(materialPort).searchMaterials(eq(7L), captor.capture());
        // 取全文时条数上限收窄到 3，否则一次就能把上下文塞满。
        assertThat(captor.getValue().limit()).isEqualTo(3);
        assertThat(result.path("matched").asInt()).isEqualTo(3);
        assertThat(result.path("items").get(0).path("contentExcerpt").asText()).isEqualTo(content);
        assertThat(result.path("items").get(0).path("truncated").asBoolean()).isFalse();
    }

    @Test
    void tellsTheAgentNotToInventKnowledgeWhenNothingMatches() {
        RecitationMaterialPort materialPort = mock(RecitationMaterialPort.class);
        when(materialPort.searchMaterials(eq(9L), any())).thenReturn(List.of());
        when(materialPort.countMaterials(eq(9L), any())).thenReturn(0L);

        JsonNode result = new QueryKnowledgeTool(materialPort)
                .execute(9L, JsonNodeFactory.instance.objectNode().put("search", "没记过的东西"),
                        new NoopProgress());

        assertThat(result.path("matched").asInt()).isZero();
        assertThat(result.path("items")).isEmpty();
        assertThat(result.path("hint").asText()).contains("不要虚构");
    }

    private MaterialView withContent(String content) {
        return new MaterialView(1L, "标题", content, "CONCEPT", "AI_QA",
                BigDecimal.valueOf(60), "RECALLING", 2, LocalDateTime.now().plusDays(1));
    }

    private MaterialView material(Long id, String title, String type, String level,
                                  int score, LocalDateTime nextReviewAt) {
        return new MaterialView(id, title, title + "的内容", type,
                "RECITATION".equals(type) ? "AUDIO_RECITATION" : "AI_QA",
                BigDecimal.valueOf(score), level, 2, nextReviewAt);
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
