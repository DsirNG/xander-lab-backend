package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JuejinPublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FakeJuejinClient client;

    @BeforeEach
    void setUp() {
        client = new FakeJuejinClient();
    }

    private JuejinPublisher publisher() {
        // resolveTagIds 只依赖 JuejinClient，凭据服务在此仅透传给 publish()，单测不触发。
        return new JuejinPublisher(new JuejinPlatformProperties(), null, client);
    }

    @Test
    void buildBriefUsesSummaryAndPadsShortSummaries() {
        assertThat(JuejinPublisher.buildBrief("太短", null)).hasSizeGreaterThanOrEqualTo(50);
        String fromContent = JuejinPublisher.buildBrief(null, "## 标题\n\n第一段有意义的正文内容。");
        assertThat(fromContent).startsWith("第一段有意义的正文内容。");
        assertThat(fromContent.length()).isBetween(50, 100);
        assertThat(JuejinPublisher.buildBrief("x".repeat(200), null)).hasSize(100);
    }

    @Test
    void buildBriefGuaranteesAtLeastFiftyChars() {
        String brief = JuejinPublisher.buildBrief("too short", null);
        assertThat(brief.length()).isBetween(50, 100);
        assertThat(brief).startsWith("too short");
    }

    @Test
    void buildBriefTruncatesSummariesOverOneHundredChars() {
        String brief = JuejinPublisher.buildBrief("长摘要".repeat(40), null);
        assertThat(brief).hasSize(100);
    }

    @Test
    void firstParagraphSkipsMarkdownPrefixes() {
        assertThat(JuejinPublisher.firstParagraph("""
                # 标题
                ## 小节
                > 引用
                3. 列表项
                这是第一段正文，足够长。
                """))
                .isEqualTo("这是第一段正文，足够长。");
    }

    @Test
    void firstParagraphReturnsNullForBlankContent() {
        assertThat(JuejinPublisher.firstParagraph("  \n  ")).isNull();
        assertThat(JuejinPublisher.firstParagraph(null)).isNull();
    }

    @Test
    void resolveTagIdsPassesNumericIdsThroughWithoutSearching() {
        JuejinPublisher publisher = publisher();
        assertThat(publisher.resolveTagIds(List.of("6809640407484334093", "6809640408797167623")))
                .containsExactly("6809640407484334093", "6809640408797167623");
        assertThat(client.searchedKeywords).isEmpty();
    }

    @Test
    void resolveTagIdsPrefersExactTagNameMatchElseFirstResult() {
        client.searchResults.put("Java", List.of(tag("6809640407613723392", "Java")));
        client.searchResults.put("Spring", List.of(tag("6809640407675007744", "Spring Boot"), tag("99", "Spring")));
        JuejinPublisher publisher = publisher();
        // 精确匹配（"Spring"）优先于排在前面的相关性结果（"Spring Boot"）
        assertThat(publisher.resolveTagIds(List.of("Java", "Spring")))
                .containsExactly("6809640407613723392", "99");
        // 完全匹配优先于第一个（相关性）结果
        assertThat(client.searchedKeywords).containsExactly("Java", "Spring");
    }

    @Test
    void resolveTagIdsSkipsUnmatchedKeywordsAndUsesTagIdFallbackField() {
        client.searchResults.put("未知标签", List.of());
        client.searchResults.put("后端", List.of(tagUsingTagIdField("6809637769959178254", "后端")));
        JuejinPublisher publisher = publisher();
        assertThat(publisher.resolveTagIds(List.of("未知标签", "后端"))).containsExactly("6809637769959178254");
    }

    @Test
    void resolveTagIdsCapsAtJuejinMaxTags() {
        client.searchResults.put("t1", List.of(tag("1", "t1")));
        client.searchResults.put("t2", List.of(tag("2", "t2")));
        client.searchResults.put("t3", List.of(tag("3", "t3")));
        client.searchResults.put("t4", List.of(tag("4", "t4")));
        JuejinPublisher publisher = publisher();
        assertThat(publisher.resolveTagIds(List.of("t1", "t2", "t3", "t4")))
                .containsExactly("1", "2", "3");
    }

    @Test
    void resolveTagIdsFailsWithClearMessageWhenNothingResolves() {
        JuejinPublisher publisher = publisher();
        assertThatThrownBy(() -> publisher.resolveTagIds(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个标签");
    }

    @Test
    void resolveTagIdsSkipsTagWhenSearchFailsAndOthersStillResolve() {
        client.searchResults.put("ok", List.of(tag("1", "ok")));
        client.searchFailures.add("caidi");
        JuejinPublisher publisher = publisher();
        assertThat(publisher.resolveTagIds(List.of("caidi", "ok"))).containsExactly("1");
    }

    private static JsonNode tag(String id, String name) {
        return MAPPER.createObjectNode().put("id", id).put("tag_name", name);
    }

    private static JsonNode tagUsingTagIdField(String tagId, String name) {
        return MAPPER.createObjectNode().put("tag_id", tagId).put("tag_name", name);
    }

    /** 只实现 searchTags 的测试替身，其余方法不应被 resolveTagIds 使用。 */
    private static final class FakeJuejinClient implements JuejinClient {
        private final java.util.Map<String, List<JsonNode>> searchResults = new java.util.HashMap<>();
        private final List<String> searchFailures = new java.util.ArrayList<>();
        private final List<String> searchedKeywords = new java.util.ArrayList<>();

        @Override
        public JsonNode searchTags(String keyword, int limit) {
            searchedKeywords.add(keyword);
            if (searchFailures.contains(keyword)) {
                throw new JuejinApiException(-1, "search failed");
            }
            return MAPPER.createObjectNode().set("data", MAPPER.valueToTree(
                    searchResults.getOrDefault(keyword, List.of())));
        }

        @Override
        public JsonNode createDraft(Credential credential, String title, String briefContent, String markContent,
                                    String categoryId, java.util.List<String> tagIds, String coverImage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode updateDraft(Credential credential, String draftId, String title, String briefContent,
                                    String markContent, String categoryId, java.util.List<String> tagIds, String coverImage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode publishDraft(Credential credential, String draftId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode fetchUserProfile(Credential credential) {
            throw new UnsupportedOperationException();
        }
    }
}