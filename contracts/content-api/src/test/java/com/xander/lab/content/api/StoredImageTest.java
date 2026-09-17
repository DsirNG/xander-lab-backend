package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 钉住 {@link StoredImage} 的字段顺序。
 *
 * <p>这个类型存在的全部理由就是<b>让调用点上的字段顺序变成可见的</b>：它的三个
 * {@code String} 字段（{@code url} / {@code originalName} / {@code storedName}）
 * 在裸参数版本里长得一样，写错顺序不会有编译错误，只会让媒体库里存下一条
 * "原始文件名其实是存储键名"的记录。具名构造器解决了调用点的问题，
 * <b>但构造器自己内部的位置仍然是位置</b> —— 所以这里用互不相同的字面量逐个钉住。</p>
 *
 * <p>实测：agent 的 {@code AgentAttachmentService} 把 {@code integration.api.UploadResponse}
 * 拆进这个类型。两个类型字段重合是刻意的（见 {@link StoredImage} 注释），
 * 所以这次拆包正是最可能写错顺序的地方。</p>
 */
class StoredImageTest {

    private static StoredImage stored() {
        return new StoredImage(
                "https://cdn.example.com/a.png",   // url
                "original-a.png",                  // originalName
                "stored/a-uuid.png",               // storedName
                4096L,                             // size
                "image/png");                      // contentType
    }

    @Test
    void everyComponentKeepsItsPositionInTheCanonicalConstructor() {
        StoredImage stored = stored();

        assertEquals("https://cdn.example.com/a.png", stored.url());
        assertEquals("original-a.png", stored.originalName());
        assertEquals("stored/a-uuid.png", stored.storedName());
        assertEquals(4096L, stored.size());
        assertEquals("image/png", stored.contentType());
    }

    @Test
    void theThreeAdjacentStringsAreMutuallyDistinguishable() {
        StoredImage stored = stored();

        assertNotEquals(stored.url(), stored.originalName(),
                "url 与 originalName 必须用不同字面量，否则顺序钉不住");
        assertNotEquals(stored.url(), stored.storedName(),
                "url 与 storedName 必须用不同字面量，否则顺序钉不住");
        assertNotEquals(stored.originalName(), stored.storedName(),
                "originalName 与 storedName 必须用不同字面量，否则顺序钉不住");
    }

    /**
     * 组件名即契约字段名。改名不会有编译错误（调用方走访问器），
     * 只会让 {@code BlogMediaService} 的映射读到错的字段。
     */
    @Test
    void componentNamesArePartOfTheContract() {
        List<String> expected = List.of("url", "originalName", "storedName", "size", "contentType");

        List<String> actual = Arrays.stream(StoredImage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(expected, actual, "字段名或顺序变了，映射逻辑要同步改");
    }

    @Test
    void theRecordLivesInTheContractPackage() {
        assertEquals("com.xander.lab.content.api", StoredImage.class.getPackageName());
    }
}
