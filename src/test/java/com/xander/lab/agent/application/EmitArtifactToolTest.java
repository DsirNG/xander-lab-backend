package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmitArtifactToolTest {

    private final EmitArtifactTool tool = new EmitArtifactTool();

    @Test
    void handsOverEveryFileWithItsLanguageSoTheCardCanRenderAFileTree() {
        ObjectNode args = args("WebSocket 聊天示例", "node",
                file("server.js", "const ws = require('ws');"),
                file("public/index.html", "<!doctype html>"),
                file("package.json", "{\"name\":\"chat\"}"));
        args.put("runHint", "npm install && node server.js");

        JsonNode result = tool.execute(new AgentToolContext(42L, 7L, 3L), args, new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("fileCount").asInt()).isEqualTo(3);
        JsonNode artifact = result.path(EmitArtifactTool.ARTIFACT_FIELD);
        assertThat(artifact.path("type").asText()).isEqualTo(EmitArtifactTool.PAYLOAD_TYPE);
        assertThat(artifact.path("id").asText()).isEqualTo("artifact-7-3");
        assertThat(artifact.path("name").asText()).isEqualTo("WebSocket 聊天示例");
        assertThat(artifact.path("framework").asText()).isEqualTo("node");
        assertThat(artifact.path("runHint").asText()).isEqualTo("npm install && node server.js");
        JsonNode first = artifact.path("files").get(0);
        assertThat(first.path("path").asText()).isEqualTo("server.js");
        assertThat(first.path("language").asText()).isEqualTo("javascript");
        assertThat(first.path("truncated").asBoolean()).isFalse();
        assertThat(artifact.path("files").get(1).path("language").asText()).isEqualTo("html");
        // 提示语要把模型推向"收口并说明怎么跑"，而不是把源码在正文里再贴一遍。
        assertThat(result.path("hint").asText()).contains("done=true").contains("不要把源码再粘一遍");
    }

    @Test
    void picksTheFirstOpenableHtmlAsTheEntryWhenTheModelDoesNotSayWhichFileToOpen() {
        JsonNode artifact = tool.execute(new AgentToolContext(42L, 7L, 3L),
                        args("", "", file("server.js", "x"), file("public/app.html", "y")), new NoopProgress())
                .path(EmitArtifactTool.ARTIFACT_FIELD);

        assertThat(artifact.path("entry").asText()).isEqualTo("public/app.html");
    }

    @Test
    void honoursTheRequestedEntryAndFallsBackToTheFirstFileWithoutAnyHtml() {
        ObjectNode withEntry = args("", "", file("main.py", "print(1)"), file("util.py", "pass"));
        withEntry.put("entry", "util.py");

        assertThat(tool.execute(new AgentToolContext(42L, 7L, 3L), withEntry, new NoopProgress())
                .path(EmitArtifactTool.ARTIFACT_FIELD).path("entry").asText()).isEqualTo("util.py");
        assertThat(tool.execute(new AgentToolContext(42L, 7L, 3L),
                        args("", "", file("main.py", "print(1)")), new NoopProgress())
                .path(EmitArtifactTool.ARTIFACT_FIELD).path("entry").asText()).isEqualTo("main.py");
    }

    @Test
    void rejectsAPathThatCouldEscapeTheProjectDirectory() {
        // 用户会照着卡片里的路径保存文件，所以盘符、.. 和目录路径一律不收，报错让模型改。
        for (String path : new String[]{"../secret.js", "C:/tmp/a.js", "src/../../a.js", "src/"}) {
            assertThatThrownBy(() -> tool.execute(new AgentToolContext(42L, 7L, 3L),
                    args("", "", file(path, "x")), new NoopProgress()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("路径不合法");
        }
        // 反斜杠、重复斜杠和开头的斜杠只是写法问题，归一化后照常收下：
        // 这里每多一次硬失败，就多一轮用户白等的重试。
        JsonNode artifact = tool.execute(new AgentToolContext(42L, 7L, 3L),
                        args("", "", file("src\\\\app//main.js", "x"), file("/public/index.html", "y")),
                        new NoopProgress())
                .path(EmitArtifactTool.ARTIFACT_FIELD);
        assertThat(artifact.path("files").get(0).path("path").asText()).isEqualTo("src/app/main.js");
        assertThat(artifact.path("files").get(1).path("path").asText()).isEqualTo("public/index.html");
    }

    @Test
    void refusesAnEmptyDeliverableInsteadOfHandingOverAHalfProject() {
        assertThatThrownBy(() -> tool.execute(new AgentToolContext(42L, 7L, 3L),
                JsonNodeFactory.instance.objectNode().put("name", "空卡"), new NoopProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("files");
        assertThatThrownBy(() -> tool.execute(new AgentToolContext(42L, 7L, 3L),
                args("", "", file("server.js", "   ")), new NoopProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有内容");
    }

    @Test
    void keepsTheCardWithinItsCapsSoOneFileCannotSwallowTheWholePayload() {
        ObjectNode manyFiles = JsonNodeFactory.instance.objectNode();
        ArrayNode files = manyFiles.putArray("files");
        for (int index = 0; index < EmitArtifactTool.MAX_FILES + 4; index++) {
            files.add(file("file" + index + ".js", "// " + index));
        }

        JsonNode capped = tool.execute(new AgentToolContext(42L, 7L, 3L), manyFiles, new NoopProgress());
        assertThat(capped.path("fileCount").asInt()).isEqualTo(EmitArtifactTool.MAX_FILES);

        JsonNode huge = tool.execute(new AgentToolContext(42L, 7L, 3L),
                        args("", "", file("big.js", "x".repeat(EmitArtifactTool.MAX_FILE_CHARS + 500))),
                        new NoopProgress())
                .path(EmitArtifactTool.ARTIFACT_FIELD);
        JsonNode only = huge.path("files").get(0);
        assertThat(only.path("content").asText()).hasSize(EmitArtifactTool.MAX_FILE_CHARS);
        // 截断要说出来：前端据此提示用户这份文件不完整，而不是让他拿走一半代码还以为是全的。
        assertThat(only.path("truncated").asBoolean()).isTrue();
    }

    @Test
    void keepsOnlyTheFirstFileOfADuplicatedPath() {
        JsonNode artifact = tool.execute(new AgentToolContext(42L, 7L, 3L),
                        args("", "", file("app.js", "first"), file("app.js", "second")), new NoopProgress())
                .path(EmitArtifactTool.ARTIFACT_FIELD);

        assertThat(artifact.path("files")).hasSize(1);
        assertThat(artifact.path("files").get(0).path("content").asText()).isEqualTo("first");
    }

    @Test
    void refusesToRunWithoutAConversationBecauseACardNeedsSomewhereToLand() {
        ObjectNode args = args("", "", file("app.js", "x"));
        assertThatThrownBy(() -> tool.execute(42L, args, new NoopProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在会话上下文中执行");
        assertThatThrownBy(() -> tool.execute(AgentToolContext.ofUser(42L), args, new NoopProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在会话上下文中执行");
    }

    @Test
    void theSchemaAndDescriptionTellTheModelWhenACardIsRequired() {
        JsonNode schema = tool.parametersSchema();

        assertThat(schema.path("required").toString()).isEqualTo("[\"files\"]");
        assertThat(schema.path("properties").path("files").path("items").path("required").toString())
                .isEqualTo("[\"path\",\"content\"]");
        assertThat(tool.name()).isEqualTo(EmitArtifactTool.NAME);
        assertThat(tool.description()).contains("完整源码").contains("什么都拿不到");
    }

    private ObjectNode args(String name, String framework, ObjectNode... files) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("name", name);
        args.put("framework", framework);
        ArrayNode target = args.putArray("files");
        for (ObjectNode file : files) {
            target.add(file);
        }
        return args;
    }

    private ObjectNode file(String path, String content) {
        return JsonNodeFactory.instance.objectNode().put("path", path).put("content", content);
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
