package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link PublishCommand} 的规范化行为。
 *
 * <p>这三条断言不是在测 getter。{@link PublishCommand} 的紧凑构造器做的两件事
 * ——null 归一与防御性拷贝——<b>是契约的一部分</b>，而它们最容易在"清理代码"时被当成
 * 冗余删掉：record 语法本身看起来已经足够，构造器里的那行像多余的。删掉之后
 * 编译通过、绝大多数测试仍然绿，只有平台适配器在收到 null 标签时 NPE。
 * 所以"重构不得静默降低既有能力"需要一条会响的断言，这就是它。</p>
 */
class PublishCommandTest {

    private static PublishCommand command(List<String> tags) {
        return new PublishCommand(1L, 2L, "title", "summary", "content", "category", null, tags);
    }

    @Test
    void nullTagsAreNormalisedToAnEmptyList() {
        // 不归一的话，两个平台适配器遍历 tags 时都会 NPE。
        assertEquals(List.of(), command(null).tags());
    }

    @Test
    void tagsAreCopiedSoLaterMutationOfTheSourceDoesNotChangeTheCommand() {
        List<String> source = new ArrayList<>(List.of("a"));
        PublishCommand command = command(source);

        source.add("b");

        assertEquals(List.of("a"), command.tags(),
                "the record must snapshot the tags: it represents a request that was already submitted");
    }

    @Test
    void tagsAreImmutable() {
        PublishCommand command = command(new ArrayList<>(List.of("a")));

        assertThrows(UnsupportedOperationException.class, () -> command.tags().add("b"));
    }

    @Test
    void theRecordItselfCarriesTheExternalPlatformCategoryId() {
        // categoryId 是外部平台的分类标识，不是本地 BlogCategory 主键。同名不同域，
        // 拆分后也不会合并；这条断言只是把这个事实写下来，避免有人"顺手"把它改成 Long。
        assertEquals("category", command(List.of()).categoryId());
        assertTrue(PublishResult.class.isRecord());
        assertTrue(BlogPublisher.class.isInterface());
    }
}
