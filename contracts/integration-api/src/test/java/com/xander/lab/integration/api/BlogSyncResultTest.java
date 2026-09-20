package com.xander.lab.integration.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link BlogSyncResult} 的分量名与顺序。
 *
 * <p>三个分量<b>全是 String</b>，{@code platform} 与 {@code url} 谁排前面编译器不会有任何意见；
 * 而它同时是前端拿到的 HTTP 响应体（{@code CsdnBlogSyncController} 直接返回它），
 * 分量名就是 JSON 字段名。所以这里用<b>互不相同</b>的字面量逐个断言 ——
 * 任何两个分量被调换都会立刻失败。</p>
 *
 * <p>⚠️ 还有一条只有反射能守的性质：这个记录必须与内容域的
 * {@code com.xander.lab.content.api.PublishResult} 在共享字段上<b>逐字相同</b>
 * （契约不许依赖契约，所以两份类型是必须的）。跨模块的那条对账断言在 {@code core-host} 的
 * {@code CsdnBlogSyncServiceTest} / {@code JuejinBlogSyncServiceTest} 里
 * （换算发生在那两个实现里，只有它们同时看得见两份类型）。这里钉住本模块这一侧的形状。</p>
 */
class BlogSyncResultTest {

    private static final String PLATFORM = "csdn";
    private static final String EXTERNAL_ID = "external-id-1";
    private static final String URL = "https://blog.csdn.net/x/1";

    @Test
    void everyComponentIsReadableBackFromItsOwnAccessor() {
        BlogSyncResult result = new BlogSyncResult(PLATFORM, EXTERNAL_ID, URL);

        assertEquals(PLATFORM, result.platform());
        assertEquals(EXTERNAL_ID, result.externalId());
        assertEquals(URL, result.url());
    }

    /**
     * 分量名与顺序即契约。改这里就是改前端拿到的 JSON 字段名。
     */
    @Test
    void theComponentsAreNamedAndOrderedExactlyLikeThis() {
        List<String> names = Arrays.stream(BlogSyncResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("platform", "externalId", "url"), names,
                "分量名或顺序变了。它们就是 HTTP 响应体的字段名，按 §11 属于契约改动。");
    }

    /**
     * 三个字面量必须互不相同 —— 否则上面那条断言会退化成"永远通过"。
     *
     * <p>这条单独存在是为了让这个<b>前提</b>本身可见：将来有人把测试常量改成同一个值，
     * 上一条不会红，这条会。</p>
     */
    @Test
    void theThreeComponentsAreDistinguishable() {
        BlogSyncResult result = new BlogSyncResult(PLATFORM, EXTERNAL_ID, URL);

        assertEquals(3, java.util.Set.of(result.platform(), result.externalId(), result.url()).size(),
                "三个测试字面量必须互不相同，否则调换分量不会有任何断言失败。");
    }

    /** 记录没有表达失败的字段："没抛"就等于"成功了"，实现不要返回空串冒充没有记录。 */
    @Test
    void carriesNoFailureField() {
        assertTrue(BlogSyncResult.class.isRecord(), "必须是 record");
        assertEquals(3, BlogSyncResult.class.getRecordComponents().length,
                "只有三个分量。加一个 error 字段就是把失败语义从异常搬到返回值上 —— 端口约定是抛异常。");
    }
}
