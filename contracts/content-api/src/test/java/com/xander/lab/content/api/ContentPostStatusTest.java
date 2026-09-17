package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link ContentPostStatus} 的三个值与"它们是编译期常量"这件事。
 *
 * <p>这里测的不是"常量等于自己" —— 那毫无意义。测的是三件改起来<b>不会有人报错</b>的事：</p>
 *
 * <ol>
 *   <li><b>数字即数据库列值。</b>{@code blog_post.status} 是 {@code int} 列，
 *       {@code BlogPostMapper} 的投影、前端响应、MCP 工具返回体都按这三个数字走。
 *       改一个数字不会编译报错（常量引用处会一起变），只会让线上已有的行改变含义：
 *       {@code 0} 变 {@code 2} 之后，所有草稿会突然从作者列表里消失。</li>
 *   <li><b>必须是编译期常量。</b>{@code BlogService.STATUS_*} 与
 *       {@code ContentPostStatus.*} 都被用作 {@code switch} 的 {@code case} 标签
 *       （{@code QueryPostsTool} / {@code BlogController} 等）。一旦有人把它改成
 *       {@code static final Integer}（去掉 {@code final} 或换成包装类型），
 *       所有 {@code case} 标签会同时编译失败 —— 这里用 {@link #describeBySwitch(int)}
 *       把"能当 case 标签用"变成一条可执行的断言，改动的人会在本测试里先看到原因，
 *       而不是在十几个调用点看到一堆 {@code constant expression required}。</li>
 *   <li><b>不能被改成枚举。</b>{@code blog_post.status} 是 {@code int} 列，
 *       MyBatis 投影与线上 JSON 都按数字走。换枚举会同时改数据库列类型与前端契约。</li>
 * </ol>
 */
class ContentPostStatusTest {

    @Test
    void theThreeValuesAreTheOnesPersistedInTheIntColumn() {
        assertEquals(0, ContentPostStatus.DRAFT, "草稿必须是 0：blog_post.status 是 int 列，已有数据按此解释");
        assertEquals(1, ContentPostStatus.PUBLISHED, "已发布必须是 1");
        assertEquals(-1, ContentPostStatus.TRASHED, "回收站必须是 -1");
    }

    /**
     * 三个值必须两两不同 —— 否则上面那条断言即使写错也看不出来，
     * 而且 {@link #describeBySwitch(int)} 会出现不可达分支。
     */
    @Test
    void theThreeValuesAreMutuallyDistinct() {
        assertTrue(ContentPostStatus.DRAFT != ContentPostStatus.PUBLISHED);
        assertTrue(ContentPostStatus.DRAFT != ContentPostStatus.TRASHED);
        assertTrue(ContentPostStatus.PUBLISHED != ContentPostStatus.TRASHED);
    }

    /**
     * 这三个常量必须能在 {@code switch} 里当 {@code case} 标签。
     *
     * <p>这不是风格检查：本方法<b>能编译</b>就等价于"三个常量都是常量表达式"。
     * 改成 {@code static final Integer} 或去掉 {@code final}，本类会直接编译失败 ——
     * 而如果不写这个方法，失败会出现在十几个调用点。</p>
     */
    @Test
    void everyValueIsACompileTimeConstantSoItStillWorksAsASwitchCaseLabel() {
        assertEquals("draft", describeBySwitch(ContentPostStatus.DRAFT));
        assertEquals("published", describeBySwitch(ContentPostStatus.PUBLISHED));
        assertEquals("trashed", describeBySwitch(ContentPostStatus.TRASHED));
        assertEquals("unknown", describeBySwitch(7));
    }

    private static String describeBySwitch(int status) {
        return switch (status) {
            case ContentPostStatus.DRAFT -> "draft";
            case ContentPostStatus.PUBLISHED -> "published";
            case ContentPostStatus.TRASHED -> "trashed";
            default -> "unknown";
        };
    }

    /**
     * 反射再确认一遍"是 {@code static final int}"：上面那条依赖的是编译器对
     * {@code case} 标签的要求，这里直接把修饰符与类型钉住，两者互为补充。
     */
    @Test
    void theConstantsAreStaticFinalInts() throws ReflectiveOperationException {
        for (String name : new String[]{"DRAFT", "PUBLISHED", "TRASHED"}) {
            Field field = ContentPostStatus.class.getField(name);
            assertEquals(int.class, field.getType(), name + " 必须是 int：数据库列是 int，换类型会改线上形状");
            assertTrue(Modifier.isStatic(field.getModifiers()), name + " 必须是 static");
            assertTrue(Modifier.isFinal(field.getModifiers()), name + " 必须是 final");
            assertTrue(Modifier.isPublic(field.getModifiers()), name + " 必须是 public");
        }
    }

    /**
     * 词表是"值 + 说明"的容器，不是可实例化的东西。私有构造器保证没人能 new 它，
     * final 保证没人能继承出第二个词表。
     */
    @Test
    void theVocabularyIsNotInstantiableAndNotExtensible() throws ReflectiveOperationException {
        assertTrue(Modifier.isFinal(ContentPostStatus.class.getModifiers()),
                "必须是 final：继承出去会造出第二份词表");
        assertFalse(ContentPostStatus.class.isEnum(),
                "⚠️ 不要改成枚举：blog_post.status 是 int 列，换枚举会同时改数据库与线上 JSON");
        assertFalse(ContentPostStatus.class.isInterface(), "词表是类，不是接口");

        Constructor<?>[] constructors = ContentPostStatus.class.getDeclaredConstructors();
        assertEquals(1, constructors.length, "只应有一个私有构造器");
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()), "构造器必须私有");
        assertEquals(0, constructors[0].getParameterCount(), "构造器不该有参数");
    }

    @Test
    void theVocabularyLivesInTheContractPackage() {
        assertEquals("com.xander.lab.content.api", ContentPostStatus.class.getPackageName());
    }
}
