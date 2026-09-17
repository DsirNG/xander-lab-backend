package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link ContentPostPort} 的<b>宽度</b>与<b>完整签名</b>。
 *
 * <p>普通接口改签名会编译报错，但端口<b>变宽</b>不会，而且四道防线一道都不响：</p>
 * <ul>
 *   <li>编译：加一个 {@code default} 方法，所有实现照常通过；</li>
 *   <li>指标：{@code api_statements} 数的是调用点的 import，不是方法个数，数字不动；</li>
 *   <li>门禁：{@code contract-dependency-boundary} 只看 import 与 POM 依赖；</li>
 *   <li>测试：没有别的测试会碰新方法。</li>
 * </ul>
 *
 * <p>于是"文章读写端口"会慢慢长成所有人绕过内容域边界的新入口 —— 每一次增加单独看都合理，
 * 合起来就是边界消失。这条断言让加方法必须<b>显式改一次测试</b>，把无意识扩张变成有意识决策。</p>
 *
 * <h2>为什么钉完整签名而不只是方法名</h2>
 * <p>只钉方法名会漏掉最危险的一类改动：<b>参数换位</b>。
 * {@code update(Long userId, Long postId, ContentPostInput input)} 把两个 {@code Long}
 * 对调，方法名不变、类型全对、编译通过，只会变成"用文章 id 当用户 id 去查归属" ——
 * 结果是永远查不到（或更糟：查到别人的）。所以这里把每个参数与返回值的
 * {@code getSimpleName()} 连顺序一起钉住。</p>
 *
 * <p><b>⚠️ 数组要先取组件类型</b>：{@code byte[].class.getName()} 是 {@code "[B"}，
 * 不以 {@code java.} 开头，直接判会误报。本端口目前没有数组参数，
 * 但类型名与框架泄漏检查都按这个规则写，避免以后加进来时误判。</p>
 */
class ContentPostPortTest {

    /**
     * 顺序即契约：这里是实现必须提供的全部能力，也是调用方能看到的一切。
     * 新增一条就要同时回答"谁需要它、为什么不放在调用方"。
     *
     * <p>刻意<b>不在</b>清单上的 16 个 {@code BlogService} 方法（{@code getManageStats} /
     * {@code getBlogs} / {@code recordView} / {@code permanentlyDeleteBlog} /
     * {@code getTitlesByIds} / {@code updateBlogStatus} / {@code getBlogById} …）都只被
     * <b>同部署单元</b>的调用方使用，规则是"跨部署单元才算数"。</p>
     */
    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "listMyPosts(Long, ContentPostQuery, int, int) -> ContentPostPage",
            "listAllMyPosts(Long, ContentPostQuery, Integer) -> List",
            "findOwned(Long, Long) -> ContentPost",
            "countManaged(Long) -> long",
            "create(Long, ContentPostInput, boolean) -> ContentPost",
            "update(Long, Long, ContentPostInput) -> ContentPost",
            "moveToTrash(Long, Long) -> void");

    @Test
    void thePortDeclaresExactlyTheSevenKnownMethodsWithTheseExactSignatures() {
        List<String> actual = declaredSignatures();

        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(), actual,
                "端口宽度或签名变了。加方法前先问：这是内容域的数据问题，还是调用方自己该做的事？");
        assertEquals(7, actual.size(), "端口方法个数必须是 7");
    }

    /**
     * 一个 {@code default} 方法可以绕过实现直接扩大契约面，而且不影响任何现有实现 ——
     * 是"变宽"最省力的路径，所以单独钉。
     */
    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        for (Method method : ContentPostPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertFalse(method.isDefault(),
                    method.getName() + " 是 default 方法：它会在不通知任何实现的情况下扩大契约面。"
                            + "把它做成抽象方法，让每个实现显式表态。");
        }
    }

    @Test
    void thePortIsAnInterfaceInTheContractPackage() {
        assertTrue(ContentPostPort.class.isInterface(), "端口必须是接口");
        assertEquals("com.xander.lab.content.api", ContentPostPort.class.getPackageName());
        assertEquals(0, ContentPostPort.class.getInterfaces().length,
                "端口不该继承别的接口：那会让宽度从别处漏进来");
    }

    /**
     * 端口签名里不许出现框架类型（Spring / Servlet / MyBatis / Redis）。
     * 门禁靠 import 检查兜底，这里再确认一遍参数类型与返回类型全是 JDK 或契约类型。
     */
    @Test
    void noMethodSignatureLeaksAFrameworkType() {
        for (Method method : ContentPostPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;

            assertContractType(method, method.getReturnType(), "返回值");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertContractType(method, parameter, "参数");
            }
        }
    }

    /**
     * 期望清单本身要自洽：有重复项的话，"方法个数 = 清单长度"就会漏掉一个真实方法。
     */
    @Test
    void theExpectedSignatureListHasNoDuplicates() {
        assertEquals(EXPECTED_SIGNATURES.size(), EXPECTED_SIGNATURES.stream().distinct().count(),
                "EXPECTED_SIGNATURES 里不能有重复项");
    }

    private static void assertContractType(Method method, Class<?> raw, String role) {
        Class<?> type = raw.isArray() ? raw.getComponentType() : raw;
        String name = type.getName();
        assertTrue(type.isPrimitive()
                        || name.startsWith("java.")
                        || name.startsWith("com.xander.lab.content.api."),
                method.getName() + " 的" + role + "里有非 JDK / 非契约类型：" + name
                        + "（跨进程传不过去，而且会把框架拖进每个依赖方的 classpath）");
    }

    private static List<String> declaredSignatures() {
        return Arrays.stream(ContentPostPort.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(ContentPostPortTest::signature)
                .sorted()
                .toList();
    }

    private static String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(ContentPostPortTest::typeName)
                .collect(Collectors.joining(", "));
        return method.getName() + "(" + parameters + ") -> " + typeName(method.getReturnType());
    }

    private static String typeName(Class<?> type) {
        return type.isArray() ? typeName(type.getComponentType()) + "[]" : type.getSimpleName();
    }
}
