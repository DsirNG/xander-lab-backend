package com.xander.lab.agent.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link RemoteToolCatalog} 的宽度与完整签名。
 *
 * <p>这个端口只有两个方法，所以"变宽"看起来不像个风险——但它正是整刀的关键：多一个
 * {@code invalidate()} 或 {@code enabled()} 之类的成员，就等于把控制流又打开了一个反向
 * 口子，而这一刀的全部意义就是让控制流只剩"消费方问、提供方答"一个方向。</p>
 */
class RemoteToolCatalogTest {

    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "tools(Long) -> List",
            "catalogVersion() -> long");

    @Test
    void thePortDeclaresExactlyTheTwoKnownMethodsWithTheseExactSignatures() {
        List<String> actual = ContractShape.declaredSignatures(RemoteToolCatalog.class);

        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(), actual,
                "端口宽度或签名变了。加方法前先问：这是消费方要问的，还是提供方想让消费方替它做的事？");
        assertEquals(2, actual.size(), "端口方法个数必须是 2");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(RemoteToolCatalog.class);
    }

    @Test
    void thePortIsAnInterfaceInTheContractPackage() {
        assertTrue(RemoteToolCatalog.class.isInterface(), "端口必须是接口");
        assertEquals("com.xander.lab.agent.spi", RemoteToolCatalog.class.getPackageName());
        assertEquals(0, RemoteToolCatalog.class.getInterfaces().length,
                "端口不该继承别的接口：那会让宽度从别处漏进来");
    }

    @Test
    void noMethodSignatureLeaksAFrameworkType() {
        ContractShape.assertNoFrameworkTypes(RemoteToolCatalog.class);
    }

    /**
     * {@code catalogVersion()} 必须是基本类型 {@code long}，不能是 {@code Long}。
     *
     * <p>{@code Long} 会多一个 {@code null} 状态，而消费方拿它和缓存里的版本比大小——
     * 一个 {@code null} 在那里要么 NPE，要么（更糟）被自动拆箱成 0，于是"版本回退到 0"
     * 看起来像一次正常变更。用 {@code long} 就没有这个状态。</p>
     */
    @Test
    void theVersionIsAPrimitiveLongSoItHasNoNullState() throws ReflectiveOperationException {
        Method version = RemoteToolCatalog.class.getMethod("catalogVersion");

        assertEquals(long.class, version.getReturnType());
    }

    /**
     * {@code tools(Long)} 的 {@code userId} 必须是装箱的 {@code Long}，不能是 {@code long}。
     *
     * <p>这一条和上一条方向相反，理由是语义不同：{@code null} 在这里是<b>有意义</b>的输入
     * ——"不限定用户"，用于 {@code names()} 这类没有归属的场景。改成 {@code long} 之后
     * 调用方只能拿 0 当哨兵，而 0 在 {@code agent_mcp_server} 里是
     * {@code PLATFORM_OWNER_ID}（平台级配置），语义正好相反：想取全部会变成"只取平台级"。</p>
     */
    @Test
    void toolsTakesABoxedUserIdBecauseNullMeansUnscoped() throws ReflectiveOperationException {
        Method tools = RemoteToolCatalog.class.getMethod("tools", Long.class);

        assertEquals(List.class, tools.getReturnType(),
                "返回 List<RemoteTool>；用数组会丢掉泛型，调用方只能强转");
        assertEquals(Long.class, tools.getParameterTypes()[0],
                "userId 必须可空：null 表示不限定用户，不是 PLATFORM_OWNER_ID");
    }

    @Test
    void theExpectedSignatureListHasNoDuplicates() {
        assertEquals(EXPECTED_SIGNATURES.size(), EXPECTED_SIGNATURES.stream().distinct().count(),
                "EXPECTED_SIGNATURES 里不能有重复项");
    }
}
