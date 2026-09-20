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
 * 钉住 {@link ContentPostPage} 的字段名与顺序。
 *
 * <p>本类型是 {@code com.xander.lab.common.PageData} 的镜像（同名、同类型、同顺序），
 * 换类型后线上 JSON 必须不变。所以这里的断言与 {@code ContentPostTest} 同源，重点是：</p>
 *
 * <ol>
 *   <li><b>组件名即 JSON 键。</b>前端分页组件读的是 {@code records} / {@code total} /
 *       {@code current} / {@code pages} / {@code size} / {@code hasMore}，改名不会有编译错误。</li>
 *   <li><b>四个相邻的 {@code long} 是位置契约。</b>{@code total} / {@code current} /
 *       {@code pages} / {@code size} 重排后类型全对、编译通过，只会让"总页数"显示成
 *       "每页大小" —— 两个都是正整数，UI 上看不出异常。</li>
 *   <li><b>刻意不做规范化。</b>{@code records} 为 {@code null} 时保持 {@code null}：
 *       {@code "records":null} 与 {@code "records":[]} 是两种不同的输入。</li>
 * </ol>
 */
class ContentPostPageTest {

    private static ContentPostPage page() {
        return new ContentPostPage(
                List.of(),   // records
                101L,        // total
                2L,          // current
                11L,         // pages
                10L,         // size
                true);       // hasMore
    }

    @Test
    void everyComponentKeepsItsPositionInTheCanonicalConstructor() {
        ContentPostPage page = page();

        assertEquals(List.of(), page.records());
        assertEquals(101L, page.total());
        assertEquals(2L, page.current());
        assertEquals(11L, page.pages());
        assertEquals(10L, page.size());
        assertEquals(true, page.hasMore());
    }

    /**
     * 四个相邻的 {@code long} 必须两两不同，否则顺序钉不住 ——
     * 用同一个字面量的话，把 {@code pages} 与 {@code size} 对调测试仍然全绿。
     */
    @Test
    void theFourAdjacentLongsAreMutuallyDistinguishable() {
        ContentPostPage page = page();

        List<Long> four = List.of(page.total(), page.current(), page.pages(), page.size());
        assertEquals(4, four.stream().distinct().count(),
                "total/current/pages/size 四个相邻 long 必须各用不同字面量，否则顺序钉不住");
    }

    @Test
    void componentNamesAreTheWireContractAndMustNotBeRenamed() {
        List<String> expected = List.of("records", "total", "current", "pages", "size", "hasMore");

        List<String> actual = Arrays.stream(ContentPostPage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(expected, actual,
                "组件名即 JSON 键，必须与 PageData 的字段名一致：改名会静默改掉前端分页组件的输入");
        assertEquals(6, actual.size(), "字段增减属于契约变更，必须显式改这里");
    }

    /**
     * {@code records} 为 {@code null} 时必须保持 {@code null}，且不做防御性拷贝 ——
     * 镜像的判据是"序列化逐字节不变"，任何归一都会破坏它。
     */
    @Test
    void recordsIsNeitherNormalizedNorCopied() {
        List<ContentPost> records = List.of();
        ContentPostPage emptyPage = new ContentPostPage(records, 0L, 1L, 0L, 10L, false);
        assertSame(records, emptyPage.records(), "records 必须原样持有，不做拷贝、不做归一");

        ContentPostPage nullRecords = new ContentPostPage(null, 0L, 1L, 0L, 10L, false);
        assertNull(nullRecords.records(),
                "records 为 null 必须保持 null：归一成空列表会把 \"records\":null 变成 \"records\":[]");
    }

    @Test
    void equalsAndHashCodeTreatTwoIdenticalPagesAsEqual() {
        assertEquals(page(), page());
        assertEquals(page().hashCode(), page().hashCode());
        assertNotEquals(page(), new ContentPostPage(List.of(), 101L, 2L, 11L, 10L, false));
    }

    @Test
    void theRecordLivesInTheContractPackageNotInADomain() {
        assertEquals("com.xander.lab.content.api", ContentPostPage.class.getPackageName());
        assertTrue(ContentPostPage.class.isRecord(), "必须是 record：契约类型不承载行为");
    }
}
