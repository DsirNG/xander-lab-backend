package com.xander.lab.agent.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * thought 是实时推给用户的一行进度，所以这里守的是"用户会不会看到模型的内部推理"。
 *
 * <p>回归来源：用户发"给我一个sse代码"，界面上直接出现了模型的原始思考
 * ——包含内部工具契约的一整段推理。提示词已经改成"写给用户看的一句进度"，
 * 但提示词是请求不是保证，这一层是保证。</p>
 */
class AgentThoughtSanitizerTest {

    private static final List<String> TOOLS =
            List.of("emit_artifact", "query_knowledge", "plan_tasks", "remember");

    @Test
    void keepsAShortUserFacingProgressLine() {
        assertThat(AgentThoughtSanitizer.sanitize("先查一下你的知识库", TOOLS))
                .isEqualTo("先查一下你的知识库");
    }

    @Test
    void dropsTheRawReasoningThatLeakedToUsersForTheSseRequest() {
        String leaked = "用户需要可运行的 SSE 示例，且是多文件代码，必须通过 artifact 工具交付。"
                + "准备提供一个零依赖的 Node.js SSE 服务端和浏览器客户端。";

        assertThat(AgentThoughtSanitizer.sanitize(leaked, TOOLS)).isEmpty();
    }

    @Test
    void dropsProgressThatNamesATool() {
        assertThat(AgentThoughtSanitizer.sanitize("调 query_knowledge 看看", TOOLS)).isEmpty();
        assertThat(AgentThoughtSanitizer.sanitize("先 EMIT_ARTIFACT 开卡", TOOLS)).isEmpty();
    }

    @Test
    void dropsProgressThatRestatesTheInternalProtocol() {
        assertThat(AgentThoughtSanitizer.sanitize("按准则必须先调用工具", TOOLS)).isEmpty();
        assertThat(AgentThoughtSanitizer.sanitize("这一步返回 done=false", TOOLS)).isEmpty();
        assertThat(AgentThoughtSanitizer.sanitize("把 args 里的参数填好", TOOLS)).isEmpty();
    }

    /** 太长就不是一行进度，而是被塞进来的推理；截断只是少泄漏一点，仍然是泄漏。 */
    @Test
    void dropsAnythingTooLongToBeAStatusLine() {
        String reasoning = "先".repeat(AgentThoughtSanitizer.MAX_STATUS_CHARS + 1);

        assertThat(AgentThoughtSanitizer.sanitize(reasoning, TOOLS)).isEmpty();
        assertThat(AgentThoughtSanitizer.sanitize("先".repeat(AgentThoughtSanitizer.MAX_STATUS_CHARS), TOOLS))
                .hasSize(AgentThoughtSanitizer.MAX_STATUS_CHARS);
    }

    /** 多行思考折成一行：进度是一行文本，换行会把它撑成一段。 */
    @Test
    void foldsWhitespaceIntoASingleLine() {
        assertThat(AgentThoughtSanitizer.sanitize("  先看你的笔记\n\n再整理成草稿  ", TOOLS))
                .isEqualTo("先看你的笔记 再整理成草稿");
    }

    @Test
    void treatsBlankThoughtAsNoProgressLine() {
        assertThat(AgentThoughtSanitizer.sanitize(null, TOOLS)).isEmpty();
        assertThat(AgentThoughtSanitizer.sanitize("   \n ", TOOLS)).isEmpty();
    }
}
