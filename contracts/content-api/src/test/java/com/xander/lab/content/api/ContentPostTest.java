package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link ContentPost} 的字段名与顺序。
 *
 * <p>这不是在测 record 的访问器 —— record 语法保证它们存在。这里钉的是三件
 * <b>编译期抓不到、运行期只在别处炸</b>的事：</p>
 *
 * <ol>
 *   <li><b>组件名就是 JSON 键。</b>本类型是 {@code publishing.api.BlogPostVO} 的镜像，
 *       换类型后线上 JSON 必须逐字节不变（这就是"本次抽取可证明是纯重构"的全部依据）。
 *       组件名一改，前端与 MCP 客户端拿到的键就变了，而<b>不会有任何编译错误</b> ——
 *       仓库内部调用方都走访问器，会一起改。</li>
 *   <li><b>顺序是位置契约。</b>这里有四组相邻同类型分量：
 *       六个 {@code String}（{@code title} → {@code coverImage}）、两个 {@code Long}
 *       （{@code id} / {@code userId}）、两个 {@code Integer}（{@code views} / {@code status}）、
 *       两个 {@code Boolean}（{@code csdnSynced} / {@code juejinSynced}）。
 *       重排不会有编译错误，只会静默换字段 —— 例如 {@code csdnSynced} 与
 *       {@code juejinSynced} 对调后，"哪些还没同步到掘金"会变成"哪些还没同步到 CSDN"，
 *       两条 SQL 都返回非空结果，不专门覆盖就发现不了。</li>
 *   <li><b>字段数。</b>新增/删除分量会改线上形状，属于 V9 §11 契约变更。
 *       数量断言让"顺手加个字段"至少要先改一次测试。</li>
 * </ol>
 *
 * <p>与 {@code BlogPostVO} 的字段集是否一致（含 {@code tagsRaw} 是否确实被排除）
 * 由应用模块的 {@code ContentPostShapeTest} 钉住 —— 契约侧看不见 {@code BlogPostVO}，
 * 这个测试必须住在 {@code apps/core-host}。</p>
 */
class ContentPostTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);

    /** 每个位置一个互不相同的字面量，任何重排都会让某一条断言失败。 */
    private static ContentPost post() {
        return new ContentPost(
                11L,                          // id
                "标题-标题",                   // title
                "摘要-摘要",                   // summary
                "正文-正文",                   // content
                "backend",                    // category
                "后端开发",                    // categoryName
                "https://cdn.example.com/c.png", // coverImage
                List.of("java", "spring"),    // tags
                22L,                          // userId
                "XanderDING",                 // author
                DATE,                         // date
                "3 min",                      // readTime
                "提示-提示",                   // tips
                800,                          // views
                ContentPostStatus.PUBLISHED,  // status
                true,                         // csdnSynced
                false);                       // juejinSynced
    }

    @Test
    void everyComponentKeepsItsPositionInTheCanonicalConstructor() {
        ContentPost post = post();

        assertEquals(11L, post.id());
        assertEquals("标题-标题", post.title());
        assertEquals("摘要-摘要", post.summary());
        assertEquals("正文-正文", post.content());
        assertEquals("backend", post.category());
        assertEquals("后端开发", post.categoryName());
        assertEquals("https://cdn.example.com/c.png", post.coverImage());
        assertEquals(List.of("java", "spring"), post.tags());
        assertEquals(22L, post.userId());
        assertEquals("XanderDING", post.author());
        assertEquals(DATE, post.date());
        assertEquals("3 min", post.readTime());
        assertEquals("提示-提示", post.tips());
        assertEquals(800, post.views());
        assertEquals(ContentPostStatus.PUBLISHED, post.status());
        assertEquals(true, post.csdnSynced());
        assertEquals(false, post.juejinSynced());
    }

    /**
     * 六个相邻的 {@code String} 必须两两不同，否则上面那条断言就失去意义：
     * 如果 {@code summary} 与 {@code content} 用同一个字面量，把它们对调测试仍然全绿 ——
     * 而那正是这次抽 DTO 想防的事故（摘要列拿到整篇正文）。
     */
    @Test
    void theSixAdjacentStringsAreMutuallyDistinguishable() {
        ContentPost post = post();

        List<String> six = List.of(post.title(), post.summary(), post.content(),
                post.category(), post.categoryName(), post.coverImage());
        assertEquals(6, six.stream().distinct().count(),
                "title/summary/content/category/categoryName/coverImage 六个相邻 String "
                        + "必须各用不同字面量，否则顺序钉不住");
    }

    /**
     * 两个 {@code Integer} 与两个 {@code Boolean} 同理：值一样就钉不住顺序。
     */
    @Test
    void theAdjacentIntegersAndBooleansAreMutuallyDistinguishable() {
        ContentPost post = post();

        assertNotEquals(post.views(), post.status(),
                "views 与 status 必须用不同字面量，否则对调了也测不出来");
        assertNotEquals(post.csdnSynced(), post.juejinSynced(),
                "csdnSynced 与 juejinSynced 必须用不同字面量，否则对调了也测不出来");
    }

    /**
     * 组件名就是序列化后的 JSON 键。改名 = 改前端与 MCP 客户端拿到的字段名，
     * 且不会有编译错误。
     */
    @Test
    void componentNamesAreTheWireContractAndMustNotBeRenamed() {
        List<String> expected = List.of(
                "id", "title", "summary", "content", "category", "categoryName",
                "coverImage", "tags", "userId", "author", "date", "readTime", "tips",
                "views", "status", "csdnSynced", "juejinSynced");

        List<String> actual = Arrays.stream(ContentPost.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(expected, actual,
                "组件名即 JSON 键：改名会静默改掉前端与 MCP 客户端看到的字段名，"
                        + "而且与 BlogPostVO 的镜像关系会断掉");
        assertEquals(17, actual.size(), "字段增减属于 V9 §11 契约变更，必须显式改这里");
    }

    /**
     * {@code tagsRaw} 必须<b>根本不存在</b>。
     *
     * <p>它是 {@code BlogPostVO} 的 {@code GROUP_CONCAT} 承接字段，靠 {@code @JsonIgnore}
     * 挡在线上 JSON 之外。契约不能带 Jackson 注解（ADR-1），所以这里选择不声明它 ——
     * 这是镜像能成立的前提：漏掉一个被忽略的字段，等于把它加回线上形状。
     * 一旦有人"顺手补全"这个字段，它就会以 {@code tagsRaw} 的名字出现在前端响应里。</p>
     */
    @Test
    void tagsRawIsDeliberatelyAbsentSoItCannotComeBackOnTheWire() {
        List<String> names = Arrays.stream(ContentPost.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertTrue(names.stream().noneMatch(name -> name.toLowerCase().contains("raw")),
                "ContentPost 不该有任何 *Raw 分量：BlogPostVO.tagsRaw 是被 @JsonIgnore 挡掉的内部字段，"
                        + "契约侧没有注解可挡，只能不声明");

        List<String> fieldNames = Arrays.stream(ContentPost.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .sorted()
                .toList();
        assertEquals(names.stream().sorted().toList(), fieldNames,
                "实例字段必须与分量一一对应：字段名与分量名不一致时，"
                        + "不同序列化框架挑哪一个就变成框架相关行为（ADR-1 要的是框架无关）");
    }

    /**
     * ⚠️ 刻意不做规范化。{@code tags} 为 {@code null} 时线上是 {@code "tags":null}，
     * 空列表是 {@code "tags":[]} —— 两者对前端与 MCP 客户端是不同的输入。
     * 紧凑构造器里"顺手归一成空列表"会直接改掉线上形状，让这次抽取不再是纯重构。
     */
    @Test
    void noCompactConstructorNormalizesTags() {
        List<String> tags = List.of("java");
        ContentPost withTags = new ContentPost(1L, "t", null, null, null, null, null,
                tags, null, null, null, null, null, null, null, null, null);
        assertSame(tags, withTags.tags(),
                "tags 必须原样持有（不做防御性拷贝、不做归一）：契约类型是数据的信封，不是加工厂");

        ContentPost withNullTags = new ContentPost(1L, "t", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertNull(withNullTags.tags(),
                "tags 为 null 必须保持 null：归一成空列表会把 \"tags\":null 变成 \"tags\":[]");

        ContentPost withEmptyTags = new ContentPost(1L, "t", null, null, null, null, null,
                List.of(), null, null, null, null, null, null, null, null, null);
        assertEquals(List.of(), withEmptyTags.tags(),
                "空列表必须保持空列表，不能被反归成 null");
    }

    /**
     * 允许全 {@code null} 构造：列表投影不返回 {@code content}、未填充时 {@code tags} 为
     * {@code null}、{@code categoryName} 只有外部同步路径才读。加校验会让那些路径抛 NPE。
     */
    @Test
    void everyComponentIsAllowedToBeNull() {
        ContentPost empty = new ContentPost(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

        assertNull(empty.id());
        assertNull(empty.title());
        assertNull(empty.date());
        assertNull(empty.status());
    }

    @Test
    void equalsAndHashCodeTreatTwoIdenticalPostsAsEqual() {
        assertEquals(post(), post());
        assertEquals(post().hashCode(), post().hashCode());
        assertNotEquals(post(), new ContentPost(99L, "标题-标题", "摘要-摘要", "正文-正文",
                "backend", "后端开发", "https://cdn.example.com/c.png", List.of("java", "spring"),
                22L, "XanderDING", DATE, "3 min", "提示-提示", 800,
                ContentPostStatus.PUBLISHED, true, false));
    }

    @Test
    void theRecordLivesInTheContractPackageNotInADomain() {
        assertEquals("com.xander.lab.content.api", ContentPost.class.getPackageName());
        assertTrue(ContentPost.class.isRecord(), "必须是 record：契约类型不承载行为");
    }
}
