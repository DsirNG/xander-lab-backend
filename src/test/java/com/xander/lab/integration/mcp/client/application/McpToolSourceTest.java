package com.xander.lab.integration.mcp.client.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentTool;
import com.xander.lab.integration.mcp.client.McpClientProperties;
import com.xander.lab.integration.mcp.client.domain.AgentMcpServer;
import com.xander.lab.integration.mcp.client.domain.McpRemoteTool;
import com.xander.lab.integration.mcp.client.infrastructure.AgentMcpServerMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolSourceTest {

    private static final long ME = 7L;
    private static final long SOMEONE_ELSE = 9L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aDisabledClientContributesNothing() {
        McpClientProperties disabled = new McpClientProperties();
        disabled.setEnabled(false);
        McpToolSource source = source(disabled, platformServer());

        assertThat(source.dynamicTools(ME)).isEmpty();
        assertThat(source.dynamicTools(null)).isEmpty();
    }

    /** 平台级配置人人可用，用户自己配的只对自己可见。 */
    @Test
    void platformServersAreVisibleToEveryoneButPersonalOnesAreNot() {
        McpToolSource source = source(enabled(), platformServer(), personalServer(SOMEONE_ELSE));

        assertThat(names(source.dynamicTools(ME))).containsExactly("mcp__platform__ping");
        assertThat(names(source.dynamicTools(SOMEONE_ELSE)))
                .containsExactly("mcp__platform__ping", "mcp__u9__act");
    }

    @Test
    void theOwnerSeesTheirOwnServer() {
        McpToolSource source = source(enabled(), platformServer(), personalServer(ME));

        assertThat(names(source.dynamicTools(ME)))
                .containsExactly("mcp__platform__ping", "mcp__u7__act");
    }

    @Test
    void aNullUserSeesEverything() {
        McpToolSource source = source(enabled(), platformServer(), personalServer(ME), personalServer(SOMEONE_ELSE));

        assertThat(names(source.dynamicTools(null)))
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

        assertThat(names(source(enabled(), server).dynamicTools(ME)))
                .containsExactly("mcp__platform__ping");
    }

    /** 缓存损坏不该让整轮对话失败：这台服务器这一轮没有工具，其余照常。 */
    @Test
    void aCorruptCatalogYieldsNoToolsInsteadOfFailingTheTurn() {
        AgentMcpServer server = platformServer();
        server.setToolsJson("{not json");

        assertThat(source(enabled(), server).dynamicTools(ME)).isEmpty();
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

        String description = source(properties, server).dynamicTools(ME).get(0).description();

        assertThat(description).startsWith("x".repeat(10) + "…").contains("平台级");
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

    private List<String> names(List<AgentTool> tools) {
        return tools.stream().map(AgentTool::name).toList();
    }
}
