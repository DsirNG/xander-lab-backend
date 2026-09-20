package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link ContentPostInput} 的字段名与顺序。
 *
 * <p>这个 DTO 存在的<b>唯一理由</b>就是消灭五个相邻的 {@code String} ——
 * {@code BlogService.updateBlog(Long, String, String, String, String, String, List)}
 * 里 {@code summary} 与 {@code content} 写反不会编译报错，只会让摘要列拿到整篇正文。
 * 所以本测试的核心断言是"五个字面量两两可区分"，其余是配套：</p>
 *
 * <ol>
 *   <li><b>顺序是位置契约。</b>跨进程序列化按顺序对齐，重排不会有编译错误。</li>
 *   <li><b>组件名即 JSON 键。</b>MCP 工具的入参按名字构造本类型，改名会让客户端的参数名对不上。</li>
 *   <li><b>刻意不做规范化。</b>{@code null} 与 {@code ""} 必须保持可区分：
 *       {@code update} 语义里前者是"不改这一项"，后者是"改成空"。</li>
 * </ol>
 */
class ContentPostInputTest {

    private static ContentPostInput input() {
        return new ContentPostInput(
                "标题-标题",                    // title
                "摘要-摘要",                    // summary
                "正文-正文",                    // content
                "frontend",                    // categoryId
                "https://cdn.example.com/a.png", // coverImage
                List.of("java", "spring"));    // tags
    }

    @Test
    void everyComponentKeepsItsPositionInTheCanonicalConstructor() {
        ContentPostInput input = input();

        assertEquals("标题-标题", input.title());
        assertEquals("摘要-摘要", input.summary());
        assertEquals("正文-正文", input.content());
        assertEquals("frontend", input.categoryId());
        assertEquals("https://cdn.example.com/a.png", input.coverImage());
        assertEquals(List.of("java", "spring"), input.tags());
    }

    /**
     * 五个相邻 {@code String} 必须两两不同。这是本类型存在的理由：
     * 只要有一个字面量重复，把对应两个分量对调后本测试仍然全绿，
     * 而那正是"摘要列拿到整篇正文"的事故形态。
     */
    @Test
    void theFiveAdjacentStringsAreMutuallyDistinguishable() {
        ContentPostInput input = input();

        List<String> five = List.of(input.title(), input.summary(), input.content(),
                input.categoryId(), input.coverImage());
        assertEquals(5, five.stream().distinct().count(),
                "title/summary/content/categoryId/coverImage 五个相邻 String 必须各用不同字面量");
    }

    @Test
    void componentNamesAreTheWireContractAndMustNotBeRenamed() {
        List<String> expected = List.of("title", "summary", "content", "categoryId", "coverImage", "tags");

        List<String> actual = Arrays.stream(ContentPostInput.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(expected, actual,
                "组件名即 JSON 键：MCP 客户端按名字构造入参，改名会让参数名对不上");
        assertEquals(6, actual.size(), "字段增减属于 V9 §11 契约变更，必须显式改这里");
    }

    /**
     * ⚠️ 不许归一。{@code tags} 为 {@code null} 是"不动标签"，空列表是"删光标签" ——
     * 紧凑构造器里顺手归一成空列表会直接改掉 {@code update} 的语义。
     */
    @Test
    void tagsDistinguishesUnchangedFromClearedAndIsNeverNormalized() {
        List<String> tags = List.of("java");
        ContentPostInput withTags = new ContentPostInput(null, null, null, null, null, tags);
        assertSame(tags, withTags.tags(), "tags 必须原样持有，不做拷贝、不做归一");

        assertNull(new ContentPostInput(null, null, null, null, null, null).tags(),
                "tags 为 null 必须保持 null —— 它是\"不改标签\"这个语义的唯一表达方式");

        assertEquals(List.of(), new ContentPostInput(null, null, null, null, null, List.of()).tags(),
                "空列表必须保持空列表 —— 它是\"删光标签\"这个语义的唯一表达方式");
    }

    /**
     * ⚠️ {@code ""} 不是 {@code null} 的同义词：前者是"改成空"，后者是"不改"。
     * 契约不做 {@code StringUtils.hasText} 这类判断，判定责任在实现方。
     */
    @Test
    void emptyStringIsKeptDistinctFromNull() {
        ContentPostInput input = new ContentPostInput("", "", "", "", "", null);

        assertEquals("", input.title(), "空字符串必须原样保留：实现方按 null 判定\"不改\"，不是按 hasText");
        assertNotEquals(null, input.title());
    }

    /** 全 null 合法：{@code update} 允许只改一项，{@code create} 的校验在实现方。 */
    @Test
    void everyComponentIsAllowedToBeNull() {
        ContentPostInput empty = new ContentPostInput(null, null, null, null, null, null);

        assertNull(empty.title());
        assertNull(empty.tags());
    }

    @Test
    void equalsAndHashCodeTreatTwoIdenticalInputsAsEqual() {
        assertEquals(input(), input());
        assertEquals(input().hashCode(), input().hashCode());
        assertNotEquals(input(), new ContentPostInput("标题-标题", "摘要-摘要", "正文-正文",
                "frontend", "https://cdn.example.com/a.png", List.of("java")));
    }

    @Test
    void theRecordLivesInTheContractPackageNotInADomain() {
        assertEquals("com.xander.lab.content.api", ContentPostInput.class.getPackageName());
        assertTrue(ContentPostInput.class.isRecord(), "必须是 record：契约类型不承载行为");
    }
}
