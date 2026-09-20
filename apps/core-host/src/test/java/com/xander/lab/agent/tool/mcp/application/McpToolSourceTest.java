package com.xander.lab.agent.tool.mcp.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.spi.RemoteTool;
import com.xander.lab.agent.tool.mcp.McpClientProperties;
import com.xander.lab.agent.tool.mcp.domain.AgentMcpServer;
import com.xander.lab.agent.tool.mcp.domain.McpRemoteTool;
import com.xander.lab.agent.tool.mcp.infrastructure.AgentMcpServerMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link McpToolSource} 作为 {@code agent.spi.RemoteToolCatalog} 的行为。
 *
 * <p>除了可见性与目录读取，这里多守一件东西：<b>目录版本号</b>。它是这条边界的核心
 * 机制——若 integration 在配置变更后反向调 agent 的
 * {@code AgentToolRegistry#invalidate()}，现在改成 agent 读这里的版本号。所以"变更后版本必须
 * 变"和"不变更时版本必须不动"两条都要钉：前者不成立改动不生效，后者不成立缓存每轮都白重建
 * （工具面就不再逐字节稳定，上游 prompt 缓存整段失效）。</p>
 */
class McpToolSourceTest {

    private static final long ME = 7L;
    private static final long SOMEONE_ELSE = 9L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aDisabledClientContributesNothing() {
        McpClientProperties disabled = new McpClientProperties();
        disabled.setEnabled(false);
        McpToolSource source = source(disabled, platformServer());

        assertThat(source.tools(ME)).isEmpty();
        assertThat(source.tools(null)).isEmpty();
    }

    /** 平台级配置人人可用，用户自己配的只对自己可见。 */
    @Test
    void platformServersAreVisibleToEveryoneButPersonalOnesAreNot() {
        McpToolSource source = source(enabled(), platformServer(), personalServer(SOMEONE_ELSE));

        assertThat(names(source.tools(ME))).containsExactly("mcp__platform__ping");
        assertThat(names(source.tools(SOMEONE_ELSE)))
                .containsExactly("mcp__platform__ping", "mcp__u9__act");
    }

    @Test
    void theOwnerSeesTheirOwnServer() {
        McpToolSource source = source(enabled(), platformServer(), personalServer(ME));

        assertThat(names(source.tools(ME)))
                .containsExactly("mcp__platform__ping", "mcp__u7__act");
    }

    @Test
    void aNullUserSeesEverything() {
        McpToolSource source = source(enabled(), platformServer(), personalServer(ME), personalServer(SOMEONE_ELSE));

        assertThat(names(source.tools(null)))
                .containsExactly("mcp__platform__ping", "mcp__u7__act", "mcp__u9__act");
    }

    /** 可见性规则本身；单独测它是因为这是唯一一处挡住跨用户泄漏的地方。 */
    @Test
    void theVisibilityRuleAdmitsPlatformRowsAndOwnRowsOnly() {
        assertThat(McpToolSource.visibleTo(platformServer(), ME)).isTrue();
        assertThat(McpToolSource.visibleTo(personalServer(ME), ME)).isTrue();
        assertThat(McpToolSource.visibleTo(personalServer(SOMEONE_ELSE), ME)).isFalse();
        assertThat(McpToolSource.visibleTo(personalServer(SOMEONE_ELSE), null)).isTrue();
    }

    /**
     * 工具名整体受 {@code agent_tool_invocation.tool_name} 的 64 字符列宽约束。
     *
     * <p>超限的名字必须在这里就被丢掉：落库那一刻才炸的话，整轮执行都会失败。</p>
     */
    @Test
    void skipsRemoteToolsWhoseComposedNameWouldOverflowTheColumn() {
        AgentMcpServer server = platformServer();
        server.setToolsJson(catalog(new McpRemoteTool("x".repeat(64), "太长", null),
                new McpRemoteTool("ping", "探活", null)));

        assertThat(names(source(enabled(), server).tools(ME)))
                .containsExactly("mcp__platform__ping");
    }

    /** 缓存损坏不该让整轮对话失败：这台服务器这一轮没有工具，其余照常。 */
    @Test
    void aCorruptCatalogYieldsNoToolsInsteadOfFailingTheTurn() {
        AgentMcpServer server = platformServer();
        server.setToolsJson("{not json");

        assertThat(source(enabled(), server).tools(ME)).isEmpty();
    }

    @Test
    void catalogRoundTripsThroughJson() {
        McpToolSource source = source(enabled());
        AgentMcpServer server = platformServer();
        server.setToolsJson(source.writeCatalog(List.of(new McpRemoteTool("ping", "探活", null))));

        assertThat(source.readCatalog(server)).extracting(McpRemoteTool::name).containsExactly("ping");
    }

    /** 远端说明会原样进提示词，长度必须由本平台封顶，而不是由第三方决定。 */
    @Test
    void truncatesRemoteDescriptionsAndMarksTheirOrigin() {
        McpClientProperties properties = enabled();
        properties.setMaxDescriptionLength(10);
        AgentMcpServer server = platformServer();
        server.setToolsJson(catalog(new McpRemoteTool("ping", "x".repeat(50), null)));

        String description = source(properties, server).tools(ME).get(0).description();

        assertThat(description).startsWith("x".repeat(10) + "…").contains("平台级");
    }

    /**
     * 什么都没变时版本必须不动。
     *
     * <p>版本一动，agent 那边的合并视图就重建一次。而"合并视图逐字节稳定"是提示词前缀能被
     * 上游缓存的前提——每轮白重建一次会让缓存整段失效，而且看不出任何症状。</p>
     */
    @Test
    void theCatalogVersionDoesNotMoveWhileNothingChanges() {
        McpToolSource source = source(enabled(), platformServer());

        long before = source.catalogVersion();
        source.tools(ME);
        source.tools(SOMEONE_ELSE);
        source.tools(null);

        assertThat(source.catalogVersion()).isEqualTo(before);
    }

    /**
     * 失效必须推进版本号，而且必须<b>同时</b>清掉自己这两层缓存。
     *
     * <p>两件事各管一半：清缓存让这里下次读到新配置；推进版本让 agent 那边的合并视图重建。
     * 只清这里，agent 永远看不见变化；只推版本不清这里，agent 重建出来的还是旧工具。</p>
     */
    @Test
    void invalidateBothBumpsTheVersionAndDropsTheCachedTools() {
        AgentMcpServer server = platformServer();
        McpToolSource source = source(enabled(), server);
        long before = source.catalogVersion();
        assertThat(names(source.tools(ME))).containsExactly("mcp__platform__ping");

        server.setToolsJson(catalog(new McpRemoteTool("pong", "换了个工具", null)));
        source.invalidate();

        assertThat(source.catalogVersion()).isGreaterThan(before);
        assertThat(names(source.tools(ME))).containsExactly("mcp__platform__pong");
    }

    /** 禁用状态下也不该动版本：没有配置变化可言，白推一次就是白重建一次。 */
    @Test
    void aDisabledClientStillReportsAStableVersion() {
        McpClientProperties disabled = new McpClientProperties();
        disabled.setEnabled(false);
        McpToolSource source = source(disabled, platformServer());

        long before = source.catalogVersion();
        source.tools(ME);

        assertThat(source.catalogVersion()).isEqualTo(before);
    }

    private McpToolSource source(McpClientProperties properties, AgentMcpServer... servers) {
        AgentMcpServerMapper mapper = mock(AgentMcpServerMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(servers));
        return new McpToolSource(properties, mapper, mock(McpConnectionFactory.class), objectMapper);
    }

    private McpClientProperties enabled() {
        McpClientProperties properties = new McpClientProperties();
        properties.setEnabled(true);
        return properties;
    }

    private AgentMcpServer platformServer() {
        AgentMcpServer server = new AgentMcpServer();
        server.setId(1L);
        server.setOwnerUserId(AgentMcpServer.PLATFORM_OWNER_ID);
        server.setServerKey("platform");
        server.setDisplayName("平台级");
        server.setEnabled(true);
        server.setToolsJson(catalog(new McpRemoteTool("ping", "探活", null)));
        return server;
    }

    private AgentMcpServer personalServer(long ownerUserId) {
        AgentMcpServer server = new AgentMcpServer();
        server.setId(ownerUserId);
        server.setOwnerUserId(ownerUserId);
        // 每台服务器一个独立标识，否则两台会拼出同名工具，断言就看不出可见性差异了。
        server.setServerKey("u" + ownerUserId);
        server.setDisplayName("用户 " + ownerUserId + " 的服务器");
        server.setEnabled(true);
        server.setToolsJson(catalog(new McpRemoteTool("act", "动作", null)));
        return server;
    }

    private String catalog(McpRemoteTool... tools) {
        try {
            return objectMapper.writeValueAsString(List.of(tools));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> names(List<RemoteTool> tools) {
        return tools.stream().map(RemoteTool::name).toList();
    }
}
