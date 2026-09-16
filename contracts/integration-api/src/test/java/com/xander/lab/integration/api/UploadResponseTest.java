package com.xander.lab.integration.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link UploadResponse} 的字段顺序与访问器映射。
 *
 * <p>这不是一组 getter 测试。构造器有 6 个参数、其中 5 个是 String，而且
 * {@code url} / {@code originalName} / {@code storedName} 三个**相邻**。
 * 谁把它们重排一下，编译器不会有任何意见，而错误会一路跑到前端拿到的 JSON 里 ——
 * 那时它看起来像"前端拿错了字段"，不像后端改错了顺序。</p>
 *
 * <p>所以这里用**互不相同**的字面量逐个断言，任何两个字段被调换都会立刻失败。
 * 按 V9 §2.3，重构不得静默降低既有能力；按 §11，字段改动属于契约改动。</p>
 */
class UploadResponseTest {

    private static final String URL = "https://cdn.example.com/u/1.png";
    private static final String ORIGINAL_NAME = "orig-name.png";
    private static final String STORED_NAME = "stored-name.png";
    private static final long SIZE = 4242L;
    private static final String CONTENT_TYPE = "image/png";
    private static final String EXTENSION = "png";

    private UploadResponse sample() {
        return new UploadResponse(URL, ORIGINAL_NAME, STORED_NAME, SIZE, CONTENT_TYPE, EXTENSION);
    }

    @Test
    void everyFieldIsReadableBackFromItsOwnAccessor() {
        UploadResponse response = sample();

        assertEquals(URL, response.getUrl());
        assertEquals(ORIGINAL_NAME, response.getOriginalName());
        assertEquals(STORED_NAME, response.getStoredName());
        assertEquals(SIZE, response.getSize());
        assertEquals(CONTENT_TYPE, response.getContentType());
        assertEquals(EXTENSION, response.getExtension());
    }

    /**
     * 三个相邻的 String 字段两两不同，且都不等于彼此。
     *
     * <p>上一条断言其实已经覆盖了这一点；这里单独再钉一次，是为了让"它们必须互不相同"
     * 这条**前提**本身可见 —— 如果将来有人把测试常量改成同一个值，
     * 上一条断言就会退化成"永远通过"，而这条会先失败。</p>
     */
    @Test
    void theAdjacentStringFieldsAreDistinguishable() {
        UploadResponse response = sample();

        assertEquals(3, java.util.Set.of(response.getUrl(), response.getOriginalName(),
                response.getStoredName()).size());
        assertTrue(response.getSize() > 0);
    }

    @Test
    void accessorsKeepTheGetPrefixThatJacksonAndExistingCallersExpect() throws Exception {
        for (String name : new String[]{"getUrl", "getOriginalName", "getStoredName",
                "getSize", "getContentType", "getExtension"}) {
            assertTrue(UploadResponse.class.getMethod(name) != null, name + " must exist");
        }
    }
}
