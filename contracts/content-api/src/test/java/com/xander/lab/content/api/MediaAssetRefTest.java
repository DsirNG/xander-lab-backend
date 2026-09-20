package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link MediaAssetRef} 的字段名与顺序。
 *
 * <p>这不是在测 record 的 getter —— record 语法保证它们存在。这里钉的是三件
 * <b>编译期抓不到、运行期只在别处炸</b>的事：</p>
 *
 * <ol>
 *   <li><b>组件名就是 JSON 键。</b>{@code BlogAgentTaskVO.illustrations} 直接进前端响应，
 *       组件名一改，前端拿到的键就变了。重命名不会有任何编译错误（本仓库内部调用方
 *       都走访问器，会一起改），只有前端静默拿不到字段。所以这里断言完整的组件名列表。</li>
 *   <li><b>{@code url} / {@code originalName} / {@code storedName} 是三个相邻的 {@code String}。</b>
 *       规范构造器里把它们重排，编译通过、类型全对，只会让调用方读到错的值 ——
 *       而"原始文件名"和"存储键名"看起来都很像文件名，人工 review 极难发现。
 *       所以用互不相同的字面量逐个断言，并额外断言这三个字段两两可区分。</li>
 *   <li><b>字段数。</b>新增一个组件会改变响应形状，属于契约变更。
 *       数量断言让"顺手加个字段"至少要先改一次测试。</li>
 * </ol>
 *
 * <p>字段集刻意与 {@code publishing.domain.BlogMediaAsset} 一一镜像，理由见该类注释：
 * 收窄字段是可行的（前端只读 3 个），但会改响应形状，应当单独做。</p>
 */
class MediaAssetRefTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 17, 10, 30, 0);

    /** 每个位置一个互不相同的字面量，任何重排都会让某一条断言失败。 */
    private static MediaAssetRef ref() {
        return new MediaAssetRef(
                11L,                                   // id
                22L,                                   // userId
                "https://cdn.example.com/a.png",       // url
                "original-a.png",                      // originalName
                "stored/a-uuid.png",                   // storedName
                4096L,                                 // size
                "image/png",                           // contentType
                800,                                   // width
                600,                                   // height
                "agent_generated",                     // sourceType
                33L,                                   // agentTaskId
                "{\"title\":\"t\"}",                   // generationMeta
                CREATED_AT);                           // createdAt
    }

    @Test
    void everyComponentKeepsItsPositionInTheCanonicalConstructor() {
        MediaAssetRef ref = ref();

        assertEquals(11L, ref.id());
        assertEquals(22L, ref.userId());
        assertEquals("https://cdn.example.com/a.png", ref.url());
        assertEquals("original-a.png", ref.originalName());
        assertEquals("stored/a-uuid.png", ref.storedName());
        assertEquals(4096L, ref.size());
        assertEquals("image/png", ref.contentType());
        assertEquals(800, ref.width());
        assertEquals(600, ref.height());
        assertEquals("agent_generated", ref.sourceType());
        assertEquals(33L, ref.agentTaskId());
        assertEquals("{\"title\":\"t\"}", ref.generationMeta());
        assertEquals(CREATED_AT, ref.createdAt());
    }

    /**
     * 三个相邻的 String 必须两两不同，否则上面那条断言就失去意义：
     * 如果 {@code url} 与 {@code originalName} 用同一个字面量，把它们对调测试仍然全绿。
     */
    @Test
    void theThreeAdjacentStringsAreMutuallyDistinguishable() {
        MediaAssetRef ref = ref();

        assertNotEquals(ref.url(), ref.originalName(),
                "url 与 originalName 必须用不同字面量，否则顺序钉不住");
        assertNotEquals(ref.url(), ref.storedName(),
                "url 与 storedName 必须用不同字面量，否则顺序钉不住");
        assertNotEquals(ref.originalName(), ref.storedName(),
                "originalName 与 storedName 必须用不同字面量，否则顺序钉不住");
    }

    /**
     * 组件名就是序列化后的 JSON 键。改名 = 改前端拿到的字段名，且不会有编译错误。
     */
    @Test
    void componentNamesAreTheWireContractAndMustNotBeRenamed() {
        List<String> expected = List.of(
                "id", "userId", "url", "originalName", "storedName", "size",
                "contentType", "width", "height", "sourceType", "agentTaskId",
                "generationMeta", "createdAt");

        List<String> actual = Arrays.stream(MediaAssetRef.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(expected, actual,
                "组件名即 JSON 键，改名会静默改掉 BlogAgentTaskVO.illustrations 的响应形状");
        assertEquals(13, actual.size(), "字段增减属于 V9 §11 契约变更，必须显式改这里");
    }

    /**
     * {@code width} / {@code height} 允许为 null：WebP 在标准 JDK 下 {@code ImageIO} 读不出来，
     * 实现会用 RIFF 签名兜底，此时尺寸未知。把它们改成 {@code int} 会让那条路径抛 NPE。
     */
    @Test
    void dimensionsMayBeNullBecauseWebpHasNoJdkReader() {
        MediaAssetRef webp = new MediaAssetRef(
                1L, 2L, "u", "o", "s", 3L, "image/webp", null, null,
                "user_upload", null, null, CREATED_AT);

        assertEquals(null, webp.width());
        assertEquals(null, webp.height());
    }

    @Test
    void equalsAndHashCodeTreatTwoIdenticalRefsAsEqual() {
        assertEquals(ref(), ref());
        assertEquals(ref().hashCode(), ref().hashCode());
    }

    @Test
    void theRecordLivesInTheContractPackageNotInADomain() {
        assertEquals("com.xander.lab.content.api", MediaAssetRef.class.getPackageName());
        assertTrue(MediaAssetRef.class.isRecord(), "必须是 record：契约类型不承载行为");
    }
}
