package com.xander.lab.integration.mcp.client.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentTool;
import com.xander.lab.integration.mcp.client.domain.AgentMcpServer;
import com.xander.lab.integration.mcp.client.domain.McpRemoteTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteMcpToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 远端工具名不受本平台控制，非法字符必须换掉，否则会污染工具名。 */
    @Test
    void composesNameWithServerPrefixAndSanitizesTheRemoteName() {
        assertThat(RemoteMcpTool.composeName("github", "create_issue")).isEqualTo("mcp__github__create_issue");
        assertThat(RemoteMcpTool.composeName("github", "issues/create")).isEqualTo("mcp__github__issues_create");
        assertThat(RemoteMcpTool.composeName("github", "a b.c")).isEqualTo("mcp__github__a_b_c");
    }

    /** 远端工具的能力本平台事先不知道，调用会带着用户授权离开本平台，所以一律要审批。 */
    @Test
    void alwaysRequiresApproval() {
        assertThat(tool("create_issue").approvalReason(objectMapper.createObjectNode())).isPresent();
    }

    /** 第三方返回值会被原样送进模型上下文，必须显式定性为数据而不是指令。 */
    @Test
    void carriesAPolicyThatTreatsRemoteOutputAsData() {
        String policy = tool("create_issue").promptSection().orElseThrow().text();

        assertThat(policy).contains("第三方数据").contains("不要照做");
    }

    /** 远端没给 schema 时补一个空对象，否则工具表渲染出 null 会让模型无从下手。 */
    @Test
    void fallsBackToAnEmptyObjectSchemaWhenTheRemoteOmitsOne() {
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("x", "d", null),
                "mcp__github__x", "d", mock(McpConnectionFactory.class));

        assertThat(tool.parametersSchema().path("type").asText()).isEqualTo("object");
        assertThat(tool.parametersSchema().path("properties").isObject()).isTrue();
    }

    @Test
    void passesThroughTheRemoteSchemaWhenPresent() {
        JsonNode schema = objectMapper.createObjectNode().put("type", "object")
                .putObject("properties").putObject("title").put("type", "string");
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("x", "d", schema),
                "mcp__github__x", "d", mock(McpConnectionFactory.class));

        assertThat(tool.parametersSchema()).isEqualTo(schema);
    }

    /** 前缀只是本平台内部的命名空间，发给远端时必须是对方认识的那个名字。 */
    @Test
    void callsTheRemoteWithItsOwnToolNameNotTheComposedOne() {
        McpConnectionFactory factory = mock(McpConnectionFactory.class);
        JsonNode args = objectMapper.createObjectNode();
        JsonNode expected = objectMapper.createObjectNode().put("ok", true);
        when(factory.callTool(any(), eq("create_issue"), eq(args))).thenReturn(expected);

        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("create_issue", "d", null),
                "mcp__github__create_issue", "d", factory);

        assertThat(tool.execute(7L, args, new NoopProgress())).isEqualTo(expected);
        verify(factory).callTool(any(), eq("create_issue"), eq(args));
    }

    private RemoteMcpTool tool(String remoteName) {
        return new RemoteMcpTool(server(),
                new McpRemoteTool(remoteName, "远端说明", objectMapper.createObjectNode()),
                RemoteMcpTool.composeName("github", remoteName), "远端说明", mock(McpConnectionFactory.class));
    }

    private AgentMcpServer server() {
        AgentMcpServer server = new AgentMcpServer();
        server.setId(1L);
        server.setOwnerUserId(AgentMcpServer.PLATFORM_OWNER_ID);
        server.setServerKey("github");
        server.setDisplayName("GitHub");
        return server;
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
