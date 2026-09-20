package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link ContentPostQuery} 的字段名与顺序。
 *
 * <p>这个 DTO 存在的<b>唯一理由</b>是消灭两个相邻的 {@code Boolean} ——
 * {@code BlogService.listMyPosts(Integer, String, Boolean, Boolean, Integer)}
 * 的第三、四个参数是 {@code juejinSynced} 与 {@code csdnSynced}。写反不会编译报错：
 * "哪些文章还没同步到掘金"会静默变成"哪些还没同步到 CSDN"，而两条 SQL
 * <b>都返回非空结果</b>，不专门覆盖就发现不了。</p>
 *
 * <p>所以本测试的核心断言是：{@code juejinSynced} 与 {@code csdnSynced} 在字面量上
 * 必须可区分（用 {@code true}/{@code false} 配不同值），并且它们在分量列表里的
 * 相对位置被钉住。其余是配套：组件名即 JSON 键、{@link ContentPostQuery#NONE} 的含义。</p>
 */
class ContentPostQueryTest {

    /**
     * 两个 {@code Boolean} 给不同的值：如果都给 {@code true}，
     * 把它们对调后本测试仍然全绿，顺序就白钉了。
     */
    private static ContentPostQuery query() {
        return new ContentPostQuery(
                ContentPostStatus.DRAFT,  // status
                "定时",                    // search
                true,                     // juejinSynced
                false);                   // csdnSynced
    }

    @Test
    void everyComponentKeepsItsPositionInTheCanonicalConstructor() {
        ContentPostQuery query = query();

        assertEquals(ContentPostStatus.DRAFT, query.status());
        assertEquals("定时", query.search());
        assertEquals(true, query.juejinSynced());
        assertEquals(false, query.csdnSynced());
    }

    /**
     * 两个相邻 {@code Boolean} 必须能区分 —— 这是本类型存在的全部理由。
     */
    @Test
    void theTwoAdjacentBooleansAreMutuallyDistinguishable() {
        ContentPostQuery query = query();

        assertNotEquals(query.juejinSynced(), query.csdnSynced(),
                "juejinSynced 与 csdnSynced 必须给不同值，否则对调了也测不出来 —— "
                        + "而这两个参数写反正是抽这个 DTO 的原因");
    }

    /**
     * {@code juejinSynced} 必须排在 {@code csdnSynced} <b>前面</b>：
     * 与{@code listMyPosts(status, search, juejinSynced, csdnSynced, limit)}
     * 参数顺序一致，这样实现侧的替换才是纯粹的"位置参数 → 具名分量"。
     */
    @Test
    void componentNamesAndOrderMatchThePositionalSignatureTheyReplace() {
        List<String> expected = List.of("status", "search", "juejinSynced", "csdnSynced");

        List<String> actual = Arrays.stream(ContentPostQuery.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(expected, actual,
                "分量顺序必须与被替换的位置参数一致（status, search, juejinSynced, csdnSynced）；"
                        + "组件名即 JSON 键，改名会改掉 MCP 工具的入参名");
        assertEquals(4, actual.size(), "字段增减属于契约变更，必须显式改这里");
    }

    /**
     * {@link ContentPostQuery#NONE} 必须真的是"全不限"。
     *
     * <p>它被 {@code BlogMcpApplicationService} 之类的调用方当作默认值用；
     * 只要有一个分量不是 {@code null}，默认查询就会静默带上一个过滤条件。</p>
     */
    @Test
    void noneMeansNoFilterAtAll() {
        assertNull(ContentPostQuery.NONE.status(), "NONE.status 必须为 null");
        assertNull(ContentPostQuery.NONE.search(), "NONE.search 必须为 null");
        assertNull(ContentPostQuery.NONE.juejinSynced(), "NONE.juejinSynced 必须为 null");
        assertNull(ContentPostQuery.NONE.csdnSynced(), "NONE.csdnSynced 必须为 null");

        assertEquals(new ContentPostQuery(null, null, null, null), ContentPostQuery.NONE,
                "NONE 必须等于手工构造的全 null 查询：它是数据，不是标记");
    }

    /**
     * {@code NONE} 必须两两不同 —— 与 {@code status = 0}（草稿）区分开：
     * 两者在 {@code equals} 上不能相等，否则"只看草稿"会被当成"不限"。
     */
    @Test
    void noneIsDistinctFromAFilterThatHappensToBeZero() {
        assertNotEquals(new ContentPostQuery(ContentPostStatus.DRAFT, null, null, null),
                ContentPostQuery.NONE,
                "status=DRAFT(0) 是过滤条件，NONE 是不限 —— 两者必须不相等");
    }

    /** 全 null 合法：调用方不需要为"不限"写一个专门的构造。 */
    @Test
    void everyComponentIsAllowedToBeNull() {
        ContentPostQuery empty = new ContentPostQuery(null, null, null, null);

        assertNull(empty.status());
        assertNull(empty.search());
    }

    @Test
    void equalsAndHashCodeTreatTwoIdenticalQueriesAsEqual() {
        assertEquals(query(), query());
        assertEquals(query().hashCode(), query().hashCode());
        assertNotEquals(query(), new ContentPostQuery(ContentPostStatus.DRAFT, "定时", false, true));
    }

    @Test
    void theRecordLivesInTheContractPackageNotInADomain() {
        assertEquals("com.xander.lab.content.api", ContentPostQuery.class.getPackageName());
        assertTrue(ContentPostQuery.class.isRecord(), "必须是 record：契约类型不承载行为");
    }
}
