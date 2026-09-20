package com.xander.lab.agent.tool.mcp.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.spi.RemoteToolCall;
import com.xander.lab.agent.tool.mcp.domain.AgentMcpServer;
import com.xander.lab.agent.tool.mcp.domain.McpRemoteTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RemoteMcpTool} 作为 {@code agent.spi.RemoteTool} 的行为。
 *
 * <p>本类实现的是契约
 * {@code agent.spi.RemoteTool}，而不是 {@code agent.application.AgentTool}：schema、参数、返回值三处 JSON 全部走文本，理由是
 * {@code AgentTool.parametersSchema()} 返回 {@code JsonNode}，而契约不许带 Jackson。
 * 所以这里除了原有的行为，还要钉住两件事：schema 的 JSON 文本与
 * {@code JsonNode} 等价，以及参数 / 结果在文本与树之间来回转换时不多不少。</p>
 */
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
        assertThat(tool("create_issue").approvalReason("{}")).isNotBlank();
    }

    /**
     * 审批理由必须点名是哪台服务器的哪个工具。
     *
     * <p>它直接显示给用户做决定，只说"要审批"等于没说——用户看不出这次会出网到哪里。</p>
     */
    @Test
    void theApprovalReasonNamesTheServerAndTheRemoteTool() {
        String reason = tool("create_issue").approvalReason("{}");

        assertThat(reason).contains("GitHub").contains("create_issue").contains("离开本平台");
    }

    /** 第三方返回值会被原样送进模型上下文，必须显式定性为数据而不是指令。 */
    @Test
    void carriesAPolicyThatTreatsRemoteOutputAsData() {
        String policy = tool("create_issue").promptText();

        assertThat(policy).contains("第三方数据").contains("不要照做");
    }

    /**
     * 远端工具一律不是只读的。
     *
     * <p>{@code readOnly} 是并发准入条件：误判成只读会让两个写操作真正并发起来。本平台对
     * 第三方工具会做什么一无所知，所以答案只能是"不"——而且必须是显式写出来的"不"，
     * 不能靠继承来的默认值（那正是 {@code AgentTool} 的 {@code default} 成员干的事）。</p>
     */
    @Test
    void isNeverReadOnly() {
        assertThat(tool("create_issue").readOnly()).isFalse();
    }

    /** 远端没给 schema 时补一个空对象，否则工具表渲染出 null 会让模型无从下手。 */
    @Test
    void fallsBackToAnEmptyObjectSchemaWhenTheRemoteOmitsOne() {
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("x", "d", null),
                "mcp__github__x", "d", mock(McpConnectionFactory.class), objectMapper);

        assertThat(tool.parametersSchemaJson()).isEqualTo("{\"type\":\"object\",\"properties\":{}}");
    }

    /**
     * 远端给了 schema 就原样透传，且<b>字段顺序不变</b>。
     *
     * <p>顺序是这条断言的重点：工具面 JSON 是进提示词前缀的，字段顺序变了前缀就变了，
     * 上游 prompt 缓存整段失效。契约把 schema 改成文本之后，这里多了一次
     * {@code toString()} → {@code readTree()}，所以必须证明它不改变序列化结果——
     * 这也正是这条边界能被证明是纯重构、不进契约变更清单的依据。</p>
     */
    @Test
    void passesThroughTheRemoteSchemaWithItsFieldOrderIntact() {
        String original = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},"
                + "\"labels\":{\"type\":\"array\"}},\"required\":[\"title\"]}";
        JsonNode schema = readTree(original);
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("x", "d", schema),
                "mcp__github__x", "d", mock(McpConnectionFactory.class), objectMapper);

        assertThat(tool.parametersSchemaJson()).isEqualTo(original);
    }

    /** 前缀只是本平台内部的命名空间，发给远端时必须是对方认识的那个名字。 */
    @Test
    void callsTheRemoteWithItsOwnToolNameNotTheComposedOne() {
        McpConnectionFactory factory = mock(McpConnectionFactory.class);
        JsonNode args = readTree("{\"title\":\"hi\"}");
        JsonNode expected = readTree("{\"ok\":true}");
        when(factory.callTool(any(), eq("create_issue"), eq(args))).thenReturn(expected);

        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("create_issue", "d", null),
                "mcp__github__create_issue", "d", factory, objectMapper);

        assertThat(tool.invoke(7L, args.toString(), new RecordingCall())).isEqualTo(expected.toString());
        verify(factory).callTool(any(), eq("create_issue"), eq(args));
    }

    /**
     * 参数以 JSON 文本进来，必须在调用传输层之前解析回结构。
     *
     * <p>不解析的话传过去的是个字符串，{@code McpConnectionFactory} 会把它当成一个标量参数
     * 发给远端——远端收到的参数表跟模型写的完全不是一回事，而且不会报错。</p>
     */
    @Test
    void parsesTheArgumentsJsonBackIntoATreeBeforeCallingTheRemote() {
        McpConnectionFactory factory = mock(McpConnectionFactory.class);
        when(factory.callTool(any(), any(), any())).thenReturn(readTree("{\"ok\":true}"));
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("x", "d", null),
                "mcp__github__x", "d", factory, objectMapper);

        tool.invoke(7L, "{\"title\":\"hi\"}", new RecordingCall());

        verify(factory).callTool(any(), eq("x"), eq(readTree("{\"title\":\"hi\"}")));
    }

    /** 没有参数时传 {@code null}，让传输层走它的"空参数表"分支。 */
    @Test
    void aBlankArgumentStringMeansNoArguments() {
        McpConnectionFactory factory = mock(McpConnectionFactory.class);
        when(factory.callTool(any(), any(), any())).thenReturn(readTree("{\"ok\":true}"));
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("x", "d", null),
                "mcp__github__x", "d", factory, objectMapper);

        tool.invoke(7L, "  ", new RecordingCall());

        verify(factory).callTool(any(), eq("x"), isNull());
    }

    /**
     * 参数不是合法 JSON 时要抛，不能带着一个 null 去调远端。
     *
     * <p>模型写出来的参数在到达这里之前已经被校验过，所以这条路径正常走不到；真走到了就是
     * 上游出了问题，而"用空参数调一次远端写接口"是比抛异常严重得多的后果。</p>
     */
    @Test
    void refusesToCallTheRemoteWithUnparsableArguments() {
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("x", "d", null),
                "mcp__github__x", "d", mock(McpConnectionFactory.class), objectMapper);

        assertThatThrownBy(() -> tool.invoke(7L, "{not json", new RecordingCall()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    /**
     * 往返<b>前后</b>各查一次取消，并在开始往返时先报一条进度。
     *
     * <p>只在前面查：用户在往返期间按了停止就得等这次往返自己超时。只在后面查：那次往返的
     * 副作用已经发生、撤不回来。顺序也要对——先报进度再出网，否则用户看不到"正在调用"。</p>
     */
    @Test
    void reportsProgressAndChecksCancellationOnBothSidesOfTheRoundTrip() {
        McpConnectionFactory factory = mock(McpConnectionFactory.class);
        when(factory.callTool(any(), any(), any())).thenReturn(readTree("{\"ok\":true}"));
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("create_issue", "d", null),
                "mcp__github__create_issue", "d", factory, objectMapper);
        RecordingCall call = new RecordingCall();

        tool.invoke(7L, "{}", call);

        assertThat(call.events).containsExactly(
                "checkCancellation", "progress:正在调用远端工具：GitHub / create_issue", "checkCancellation");
    }

    /** 契约说 {@code invoke} 不得返回空；传输层真返回 null 时也不能把它漏给调用侧。 */
    @Test
    void neverReturnsAnEmptyResultEvenIfTheTransportLayerReturnsNull() {
        McpConnectionFactory factory = mock(McpConnectionFactory.class);
        when(factory.callTool(any(), any(), any())).thenReturn(null);
        RemoteMcpTool tool = new RemoteMcpTool(server(), new McpRemoteTool("x", "d", null),
                "mcp__github__x", "d", factory, objectMapper);

        assertThat(tool.invoke(7L, "{}", new RecordingCall())).isNotBlank().contains("isError");
    }

    private RemoteMcpTool tool(String remoteName) {
        return new RemoteMcpTool(server(),
                new McpRemoteTool(remoteName, "远端说明", objectMapper.createObjectNode()),
                RemoteMcpTool.composeName("github", remoteName), "远端说明",
                mock(McpConnectionFactory.class), objectMapper);
    }

    private AgentMcpServer server() {
        AgentMcpServer server = new AgentMcpServer();
        server.setId(1L);
        server.setOwnerUserId(AgentMcpServer.PLATFORM_OWNER_ID);
        server.setServerKey("github");
        server.setDisplayName("GitHub");
        return server;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 记录进度与取消检查的调用序列，用来钉住往返前后的顺序。 */
    private static final class RecordingCall implements RemoteToolCall {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onProgress(String message) {
            events.add("progress:" + message);
        }

        @Override
        public void checkCancellation() {
            events.add("checkCancellation");
        }
    }
}
