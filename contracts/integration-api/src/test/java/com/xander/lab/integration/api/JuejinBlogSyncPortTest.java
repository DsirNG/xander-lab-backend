package com.xander.lab.integration.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link JuejinBlogSyncPort} 的<b>宽度</b>与<b>完整签名</b>。
 *
 * <p>理由与 {@link CsdnBlogSyncPortTest} 相同。这里额外钉住的是<b>那四个参数</b>：
 * 掘金的同步比 CSDN 多收分类 id 与标签 id，而这两个参数正是"CSDN 与掘金为什么是两个端口"
 * 的全部理由 —— 它们一旦被"没人用"为理由砍掉，指定掘金分类/标签这项能力就在拆服务时
 * 静默消失了（V9 §2.3 禁止静默降级）。</p>
 *
 * <p>⚠️ 跨域的两个消费方（{@code agent} 的 {@code PublishPostTool} 与 {@code automation} 的
 * {@code BlogPlanRunService}）目前一律传 {@code (null, null)}，但同域的前端 HTTP 层
 * （{@code JuejinBlogSyncController}）会传真实值，而跨进程之后端口是那条路径唯一能穿过的形状。</p>
 */
class JuejinBlogSyncPortTest {

    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "sync(Long, Long, String, List) -> BlogSyncResult",
            "findSucceeded(Long, Long) -> BlogSyncResult");

    @Test
    void declaresExactlyTheTwoKnownMethodsWithTheseExactSignatures() {
        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(),
                ContractShape.declaredSignatures(JuejinBlogSyncPort.class),
                "端口宽度或签名变了。加方法前先问：这是 integration 域的同步能力，还是调用方自己该做的事？");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(JuejinBlogSyncPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackageAndExtendsNothing() {
        assertTrue(JuejinBlogSyncPort.class.isInterface(), "端口必须是接口。");
        assertEquals("com.xander.lab.integration.api", JuejinBlogSyncPort.class.getPackageName());
        assertEquals(0, JuejinBlogSyncPort.class.getInterfaces().length,
                "端口不许 extends 别的接口：契约不能依赖契约。");
    }

    @Test
    void everyMethodTakesTheCallerIdExplicitlyAsItsFirstParameter() {
        ContractShape.assertFirstParameterIsTheCallerId(JuejinBlogSyncPort.class);
    }

    @Test
    void carriesNoFrameworkTypesInAnySignature() {
        ContractShape.assertNoFrameworkTypes(JuejinBlogSyncPort.class);
    }

    /**
     * 掘金那条 {@code sync} 的四个参数<b>逐个点名</b>，一个都不能少、顺序不能变。
     *
     * <p>前两个是 {@code Long}（对调不会有编译错误），后两个是掘金专有：
     * 分类 id 是 {@code String}、标签 id 列表是 {@code List<String>}。
     * 这条断言同时钉住"标签 id 不是 {@code List<Long>}"—— 掘金的 tag_id 在 HTTP 层
     * 以字符串形式传递，改成 {@code Long} 会在前端传大数字时静默丢精度。</p>
     */
    @Test
    void syncTakesTheArticlesIdAndJuejinsOwnCategoryAndTagIds() throws ReflectiveOperationException {
        Method sync = JuejinBlogSyncPort.class.getMethod(
                "sync", Long.class, Long.class, String.class, List.class);

        assertEquals(BlogSyncResult.class, sync.getReturnType());
        assertEquals(List.of("userId", "postId", "juejinCategoryId", "juejinTagIds"),
                java.util.Arrays.stream(sync.getParameters()).map(java.lang.reflect.Parameter::getName).toList(),
                "四个参数的顺序与名字就是契约。前两个对调不会有编译错误，"
                        + "后两个被砍掉则是能力静默降级。");
    }

    @Test
    void returnsTheContractRecordAndNotContentApisPublishResult() throws ReflectiveOperationException {
        assertEquals(BlogSyncResult.class,
                JuejinBlogSyncPort.class.getMethod("findSucceeded", Long.class, Long.class).getReturnType());
    }
}
