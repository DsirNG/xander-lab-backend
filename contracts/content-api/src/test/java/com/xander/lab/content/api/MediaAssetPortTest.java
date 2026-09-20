package com.xander.lab.content.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link MediaAssetPort} 的<b>宽度</b>。
 *
 * <p>普通接口改签名会编译报错，但端口<b>变宽</b>不会，而且四道防线一道都不响：</p>
 * <ul>
 *   <li>编译：加一个 {@code default} 方法，所有实现照常通过；</li>
 *   <li>指标：{@code api_statements} 数的是调用点的 import，不是方法个数，数字不动；</li>
 *   <li>门禁：{@code contract-dependency-boundary} 只看 import 与 POM 依赖；</li>
 *   <li>测试：没有别的测试会碰新方法。</li>
 * </ul>
 *
 * <p>于是"媒体库端口"会慢慢长成所有人绕过内容域边界的新入口 —— 每一次增加单独看都合理，
 * 合起来就是边界消失。这条断言让加方法必须<b>显式改一次测试</b>，把无意识扩张变成有意识决策。</p>
 *
 * <p>给端口加一个 {@code default String debug()} 之后，
 * 反应堆<b>编译仍然通过</b>，其余测试全绿，<b>只有这一条红</b>。这正是它存在的理由。</p>
 */
class MediaAssetPortTest {

    /**
     * 顺序即契约：这里是实现必须提供的全部能力，也是调用方能看到的一切。
     * 新增一条就要同时回答"谁需要它、为什么不放在调用方"。
     */
    private static final List<String> EXPECTED_METHODS = List.of(
            "recordStored",
            "recordGenerated",
            "uploadImage",
            "findOwned",
            "listByTask",
            "findReusableGenerated");

    @Test
    void thePortDeclaresExactlyTheSixKnownMethods() {
        List<String> actual = Arrays.stream(MediaAssetPort.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .sorted()
                .toList();

        List<String> expected = EXPECTED_METHODS.stream().sorted().toList();

        assertEquals(expected, actual,
                "端口宽度变了。加方法前先问：这是内容域的数据问题，还是调用方自己该做的事？");
        assertEquals(6, actual.size());
    }

    /**
     * 一个 {@code default} 方法可以绕过实现直接扩大契约面，而且不影响任何现有实现 ——
     * 是"变宽"最省力的路径，所以单独钉。
     */
    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        for (Method method : MediaAssetPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertFalse(method.isDefault(),
                    method.getName() + " 是 default 方法：它会在不通知任何实现的情况下扩大契约面。"
                            + "把它做成抽象方法，让每个实现显式表态。");
        }
    }

    @Test
    void thePortIsAnInterfaceInTheContractPackage() {
        assertTrue(MediaAssetPort.class.isInterface(), "端口必须是接口");
        assertEquals("com.xander.lab.content.api", MediaAssetPort.class.getPackageName());
        assertEquals(0, MediaAssetPort.class.getInterfaces().length,
                "端口不该继承别的接口：那会让宽度从别处漏进来");
    }

    /**
     * 端口签名里不许出现框架类型（Spring / Servlet / MyBatis / Redis）。
     * 门禁靠 import 检查兜底，这里再确认一遍参数类型全是 JDK 或契约类型。
     *
     * <p>⚠️ 数组要取组件类型再判：{@code byte[].class.getName()} 是 {@code "[B"}，
     * 不以 {@code java.} 开头，直接判会误报 —— 而 {@code byte[]} 正是本端口刻意选的形状。</p>
     */
    @Test
    void noMethodSignatureLeaksAFrameworkType() {
        for (Method method : MediaAssetPort.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            for (Class<?> parameter : method.getParameterTypes()) {
                Class<?> type = parameter.isArray() ? parameter.getComponentType() : parameter;
                String name = type.getName();
                assertTrue(type.isPrimitive()
                                || name.startsWith("java.")
                                || name.startsWith("com.xander.lab.content.api."),
                        method.getName() + " 的参数里有非 JDK / 非契约类型：" + name
                                + "（跨进程传不过去，而且会把框架拖进每个依赖方的 classpath）");
            }
        }
    }

    /**
     * 期望清单本身要自洽：有重复项的话，"方法个数 = 清单长度"就会漏掉一个真实方法。
     */
    @Test
    void theExpectedMethodListHasNoDuplicates() {
        assertEquals(EXPECTED_METHODS.size(), EXPECTED_METHODS.stream().distinct().count(),
                "EXPECTED_METHODS 里不能有重复项");
    }
}
