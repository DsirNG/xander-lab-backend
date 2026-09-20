package com.xander.lab.integration.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link CsdnBlogSyncPort} 的<b>宽度</b>与<b>完整签名</b>。
 *
 * <p>为什么只有反射能守：端口"变宽"（加方法、加 {@code default}、改参数表）不会触发任何一道
 * 其它防线 —— 编译过、耦合指标一动不动、26 条门禁全绿、单元测试也全绿。
 * 这里是 {@code agent → integration} 那条跨部署单元边的边界，变宽的后果是
 * {@code integration} 域的编排能力被逐个搬进 {@code agent}。</p>
 *
 * <p>⚠️ 参数顺序必须一起钉：{@code sync(Long userId, Long postId)} 里两个 {@code Long} 对调，
 * 方法名不变、类型全对、编译通过 —— 结果是"拿 postId 当用户 id 去查文章属主"，
 * 通常查不到（返回 null 或抛"文章不存在"），偶尔查到别人的。</p>
 */
class CsdnBlogSyncPortTest {

    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "sync(Long, Long) -> BlogSyncResult",
            "findSucceeded(Long, Long) -> BlogSyncResult");

    @Test
    void declaresExactlyTheTwoKnownMethodsWithTheseExactSignatures() {
        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(),
                ContractShape.declaredSignatures(CsdnBlogSyncPort.class),
                "端口宽度或签名变了。加方法前先问：这是 integration 域的同步能力，还是调用方自己该做的事？"
                        + "有意加就一起改预期清单，并在设计文档里记下为什么。");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(CsdnBlogSyncPort.class);
    }

    @Test
    void isAnInterfaceInTheContractPackageAndExtendsNothing() {
        assertTrue(CsdnBlogSyncPort.class.isInterface(), "端口必须是接口。");
        assertEquals("com.xander.lab.integration.api", CsdnBlogSyncPort.class.getPackageName(),
                "包名必须是 integration.api —— 模块名 integration-api 决定了它。");
        assertEquals(0, CsdnBlogSyncPort.class.getInterfaces().length,
                "端口不许 extends 别的接口：契约不能依赖契约，继承会把别的契约的方法并进来。");
    }

    /**
     * 两个方法都必须把调用方身份作为第一个参数，且参数<b>名</b>必须是 {@code userId}。
     *
     * <p>这是三个 {@code userId} 守卫形态里的第一种（正面断言）：本端口<b>收</b>身份。
     * 另两种是"不产身份也不收"（{@code BlogAgentImagePort} 的反面断言）与
     * "产出身份所以不许收"（{@code AccessTokenLookupPort}）。没有第四种。</p>
     */
    @Test
    void everyMethodTakesTheCallerIdExplicitlyAsItsFirstParameter() {
        ContractShape.assertFirstParameterIsTheCallerId(CsdnBlogSyncPort.class);
    }

    @Test
    void carriesNoFrameworkTypesInAnySignature() {
        ContractShape.assertNoFrameworkTypes(CsdnBlogSyncPort.class);
    }

    /**
     * 返回类型必须是契约记录，不能是内容域的 {@code PublishResult}、也不能是域侧实体。
     *
     * <p>契约不许依赖契约，所以本端口<b>不能</b>直接返回
     * {@code com.xander.lab.content.api.PublishResult} —— 那会把 {@code content-api}
     * 写进本模块的 POM。换算落在实现边界。</p>
     */
    @Test
    void returnsTheContractRecordAndNotContentApisPublishResult() throws ReflectiveOperationException {
        for (String name : List.of("sync", "findSucceeded")) {
            Method method = CsdnBlogSyncPort.class.getMethod(name, Long.class, Long.class);
            assertEquals(BlogSyncResult.class, method.getReturnType(),
                    name + " 必须返回本模块的 BlogSyncResult。");
        }
    }
}
