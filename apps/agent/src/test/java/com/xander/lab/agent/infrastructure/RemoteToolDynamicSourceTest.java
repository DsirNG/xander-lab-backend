package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentDynamicToolSource;
import com.xander.lab.agent.application.AgentTool;
import com.xander.lab.agent.spi.RemoteTool;
import com.xander.lab.agent.spi.RemoteToolCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RemoteToolDynamicSource} 的包装与版本折叠。
 *
 * <p>它是 agent 侧的入站适配器，干两件事：把每个契约实现产出的 {@link RemoteTool} 包成
 * {@link AgentTool}；把多个提供方的版本号折成一个给注册表用。第一件事错了会少工具，
 * 第二件事错了会<b>永远</b>少工具——所以两件都要钉。</p>
 */
class RemoteToolDynamicSourceTest {

    private static final long ME = 7L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void wrapsEveryCatalogIntoAgentTools() {
        StubCatalog first = new StubCatalog();
        first.tools = List.of(new StubRemoteTool("mcp__platform__ping"));
        StubCatalog second = new StubCatalog();
        second.tools = List.of(new StubRemoteTool("mcp__mine__do"), new StubRemoteTool("mcp__mine__act"));

        assertThat(names(source(first, second).dynamicTools(ME)))
                .containsExactly("mcp__platform__ping", "mcp__mine__do", "mcp__mine__act");
    }

    /** 契约已经说了要过滤空值，但过滤必须真的在——一个 null 会一路炸到渲染工具面那一步。 */
    @Test
    void skipsNullToolsAndToolsWithoutAName() {
        StubCatalog catalog = new StubCatalog();
        catalog.tools = new ArrayList<>();
        catalog.tools.add(null);
        catalog.tools.add(new StubRemoteTool((String) null));
        catalog.tools.add(new StubRemoteTool("mcp__ok__tool"));

        assertThat(names(source(catalog).dynamicTools(ME))).containsExactly("mcp__ok__tool");
    }

    @Test
    void aCatalogThatReturnsNullContributesNothing() {
        StubCatalog catalog = new StubCatalog();
        catalog.tools = null;

        assertThat(source(catalog).dynamicTools(ME)).isEmpty();
    }

    /**
     * 一个提供方取不到工具，不能连带把别的提供方的工具也抹掉。
     *
     * <p>注册表是按"来源"兜异常的——它捕获 {@code dynamicTools} 抛出的异常并跳过<b>整个</b>
     * 来源。所以兜底必须在这里再做一层、按提供方粒度兜：否则一台远端服务器抖动，工具面里
     * 所有来源的工具会一起消失。</p>
     */
    @Test
    void oneFailingCatalogDoesNotHideTheOthers() {
        StubCatalog failing = new StubCatalog();
        failing.throwsOnRead = true;
        StubCatalog healthy = new StubCatalog();
        healthy.tools = List.of(new StubRemoteTool("mcp__platform__ping"));

        assertThat(names(source(failing, healthy).dynamicTools(ME)))
                .containsExactly("mcp__platform__ping");
    }

    /** 一次都没配远端服务器时，来源必须仍然可用（返回空、版本 0），不能 NPE。 */
    @Test
    void noCatalogsMeansVersionZeroAndNoTools() {
        RemoteToolDynamicSource source = source();

        assertThat(source.dynamicTools(ME)).isEmpty();
        assertThat(source.version()).isZero();
        // 版本 0 是"永不变化"的语义，与 AgentDynamicToolSource#version() 的默认值一致：
        // 没有动态工具时注册表不该反复重建合并视图。
        AgentDynamicToolSource stateless = userId -> List.of();
        assertThat(source.version()).isEqualTo(stateless.version());
    }

    /** 任何一个提供方换了工具，折叠出来的版本就必须变，否则注册表永远不重建。 */
    @Test
    void theVersionChangesWheneverAnyCatalogVersionChanges() {
        StubCatalog first = new StubCatalog();
        StubCatalog second = new StubCatalog();
        RemoteToolDynamicSource source = source(first, second);

        long initial = source.version();
        assertThat(source.version()).isEqualTo(initial);

        first.version = 1;
        long afterFirst = source.version();
        assertThat(afterFirst).isNotEqualTo(initial);

        second.version = 1;
        long afterSecond = source.version();
        assertThat(afterSecond).isNotEqualTo(afterFirst);
    }

    /**
     * 折叠是<b>单射</b>的：两个不同的版本向量不会折成同一个值。
     *
     * <p>这不是理论洁癖。折成同一个值的后果是"其中一个提供方换了工具，注册表却认为版本没变，
     * 于是永远不重建"——没有报错、没有日志，只是那个提供方的工具面停在旧状态。Javadoc 里的
     * 证明是"版本单调不减 + 来源个数在 bean 存活期内固定"，这里用穷举把它钉住。</p>
     */
    @Test
    void distinctVersionPairsNeverFoldToTheSameValue() {
        Map<Long, String> seen = new HashMap<>();
        for (long first = 0; first <= 8; first++) {
            for (long second = 0; second <= 8; second++) {
                String vector = first + "/" + second;
                long folded = source(catalogAt(first), catalogAt(second)).version();
                assertThat(seen.putIfAbsent(folded, vector))
                        .as("折叠碰撞：" + vector + " 与 " + seen.get(folded) + " 都折成 " + folded)
                        .isNull();
            }
        }
    }

    private RemoteToolDynamicSource source(RemoteToolCatalog... catalogs) {
        return new RemoteToolDynamicSource(List.of(catalogs), objectMapper);
    }

    private StubCatalog catalogAt(long version) {
        StubCatalog catalog = new StubCatalog();
        catalog.version = version;
        return catalog;
    }

    private List<String> names(List<AgentTool> tools) {
        return tools.stream().map(AgentTool::name).toList();
    }

    /** 一个可观察的提供方；默认什么都不给、版本 0。 */
    private static final class StubCatalog implements RemoteToolCatalog {

        private List<RemoteTool> tools = List.of();
        private long version;
        private boolean throwsOnRead;

        @Override
        public List<RemoteTool> tools(Long userId) {
            if (throwsOnRead) throw new IllegalStateException("远端不可达");
            return tools;
        }

        @Override
        public long catalogVersion() {
            return version;
        }
    }
}
