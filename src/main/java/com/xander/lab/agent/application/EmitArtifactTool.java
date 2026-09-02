package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把写好的代码作为交付卡发给用户：卡片里是文件树加完整源码，可以逐个文件复制或下载。
 *
 * <p>为什么需要它：模型本来可以把代码写在 reply 的围栏里，但多文件交付（服务端 + 客户端、
 * HTML + CSS + JS、配置 + 源码）挤在一段 Markdown 里既看不清也拿不走；更常见的失败是
 * 模型只留一句“已完成 XX 示例”就收口，用户翻遍回复找不到任何东西。交付卡把“文件”变成
 * 结构化负载，于是“到底有没有交付”成了服务端能检查的事实，而不是一句自称。</p>
 *
 * <p>两三行的小片段仍然写在 reply 的围栏里，不必为此开一张卡。</p>
 */
@Component
public class EmitArtifactTool implements AgentTool {

    /** 编排循环据此把工具结果提升成 kind=artifact 的消息和 artifact 事件。 */
    public static final String NAME = "emit_artifact";
    /** 结果里承载交付负载的字段名，编排循环按它取出要下发的内容。 */
    public static final String ARTIFACT_FIELD = "artifact";
    /** 前端按这个 type 认卡。 */
    public static final String PAYLOAD_TYPE = "artifact";

    /** 单张卡的文件数上限：再多就不是“看一眼就能跑”的示例了。 */
    static final int MAX_FILES = 12;
    /** 单个文件的字符上限，超出截断并标记，免得一份文件吃掉整张卡。 */
    static final int MAX_FILE_CHARS = 20000;
    /** 整张卡的字符上限。 */
    static final int MAX_TOTAL_CHARS = 60000;
    static final int MAX_NAME_CHARS = 60;
    static final int MAX_PATH_CHARS = 120;
    static final int MAX_HINT_CHARS = 300;

    /** 扩展名到高亮语言的映射：前端据此选高亮规则，认不出的按纯文本渲染。 */
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("js", "javascript"), Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("jsx", "jsx"), Map.entry("tsx", "tsx"),
            Map.entry("html", "html"), Map.entry("htm", "html"), Map.entry("css", "css"),
            Map.entry("scss", "scss"), Map.entry("json", "json"), Map.entry("md", "markdown"),
            Map.entry("py", "python"), Map.entry("java", "java"), Map.entry("go", "go"),
            Map.entry("rs", "rust"), Map.entry("sql", "sql"), Map.entry("sh", "bash"),
            Map.entry("bash", "bash"), Map.entry("yml", "yaml"), Map.entry("yaml", "yaml"),
            Map.entry("vue", "vue"), Map.entry("xml", "xml"), Map.entry("env", "ini"));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把你写好的代码作为交付卡发给用户：卡片带文件树和完整源码，用户可以逐个文件复制或下载。"
                + "使用时机：用户要的是可运行的代码、示例、项目或脚本，尤其是需要多个文件时"
                + "（服务端 + 客户端、HTML + CSS + JS、配置 + 源码），必须调用本工具把每个文件的完整内容交出来。"
                + "只在 reply 里写“已完成 XX 示例”而不调用本工具，用户实际上什么都拿不到。"
                + "两三行的小片段不必开卡，直接写在 reply 的代码围栏里即可。"
                + "每个文件都要给出可直接运行的完整内容，不允许用“此处省略”“同上”或空注释占位；"
                + "最多 " + MAX_FILES + " 个文件。"
                + "调用成功后以 done=true 收口，reply 只写怎么跑起来、依赖和注意事项，不要把源码再粘一遍。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("name")
                .put("type", "string")
                .put("description", "交付物名称，例如“WebSocket 聊天示例”，最多 " + MAX_NAME_CHARS + " 个字符");
        properties.putObject("framework")
                .put("type", "string")
                .put("description", "技术栈标签，例如 node、react、static、python，可选");
        properties.putObject("entry")
                .put("type", "string")
                .put("description", "入口文件路径，需与 files 里的某个 path 一致；留空则自动取第一个 html 或第一个文件");
        properties.putObject("runHint")
                .put("type", "string")
                .put("description", "怎么运行，例如“npm install && node server.js 后打开 http://localhost:3000”，可选");
        ObjectNode files = properties.putObject("files");
        files.put("type", "array");
        files.put("description", "文件列表，按阅读顺序排列，最多 " + MAX_FILES + " 个");
        ObjectNode file = files.putObject("items");
        file.put("type", "object");
        ObjectNode fileProperties = file.putObject("properties");
        fileProperties.putObject("path")
                .put("type", "string")
                .put("description", "项目内相对路径，例如 server.js、public/index.html；不要用绝对路径或 ..");
        fileProperties.putObject("content")
                .put("type", "string")
                .put("description", "该文件的完整源码，单个文件最多 " + MAX_FILE_CHARS + " 个字符");
        file.putArray("required").add("path").add("content");
        schema.putArray("required").add("files");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        throw new IllegalStateException("emit_artifact 必须在会话上下文中执行");
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        // 交付卡是发给某一个会话的界面，没有会话就没有下发目标，不如报错让模型改用 reply 里的围栏。
        if (!context.hasConversation()) {
            throw new IllegalStateException("emit_artifact 必须在会话上下文中执行");
        }
        ObjectNode artifact = JsonNodeFactory.instance.objectNode();
        artifact.put("type", PAYLOAD_TYPE);
        artifact.put("id", "artifact-" + context.conversationId() + "-" + context.runVersion());
        artifact.put("name", limit(args.path("name").asText(""), MAX_NAME_CHARS));
        artifact.put("framework", limit(args.path("framework").asText(""), MAX_NAME_CHARS));
        artifact.put("runHint", limit(args.path("runHint").asText(""), MAX_HINT_CHARS));
        ArrayNode files = artifact.putArray("files");
        appendFiles(files, args.path("files"));
        artifact.put("entry", pickEntry(files, args.path("entry").asText("")));

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("fileCount", files.size());
        outcome.set(ARTIFACT_FIELD, artifact);
        outcome.put("hint", "代码已经作为交付卡发给用户了，文件树和源码都在卡片里，用户可以复制或下载。"
                + "现在就以 done=true 收口，reply 只写怎么跑起来和注意事项，不要把源码再粘一遍。");
        return outcome;
    }

    /**
     * 归一化文件列表。
     *
     * <p>路径不合法或内容为空时直接报错而不是悄悄跳过：交付卡少一个文件，用户拿到的就是跑不起来的
     * 半成品，而这正是这张卡要解决的问题。报错让模型带着原因重试一次。</p>
     */
    private void appendFiles(ArrayNode target, JsonNode files) {
        if (!files.isArray() || files.isEmpty()) {
            throw new IllegalArgumentException("emit_artifact 需要非空的 files 数组");
        }
        Set<String> seen = new LinkedHashSet<>();
        int budget = MAX_TOTAL_CHARS;
        for (JsonNode file : files) {
            String rawPath = file.path("path").asText("").trim();
            String content = file.path("content").asText("");
            // 整条都是空的（模型多写了一个占位元素）才跳过。
            if (rawPath.isEmpty() && content.isBlank()) continue;
            String path = sanitizePath(rawPath);
            if (path.isEmpty()) {
                throw new IllegalArgumentException("emit_artifact 的文件路径不合法："
                        + limit(rawPath, 60) + "，请用项目内相对路径");
            }
            if (content.isBlank()) {
                throw new IllegalArgumentException("emit_artifact 的文件 " + path + " 没有内容，请写出完整源码");
            }
            // 同路径只保留第一份：文件树里出现两个同名条目，用户不知道该存哪一个。
            if (!seen.add(path)) continue;
            int allowance = Math.min(MAX_FILE_CHARS, budget);
            boolean truncated = content.length() > allowance;
            ObjectNode entry = target.addObject();
            entry.put("path", path);
            entry.put("language", languageOf(path));
            entry.put("content", truncated ? content.substring(0, allowance) : content);
            entry.put("truncated", truncated);
            budget -= Math.min(content.length(), allowance);
            if (target.size() >= MAX_FILES || budget <= 0) break;
        }
        if (target.isEmpty()) {
            throw new IllegalArgumentException("emit_artifact 的每个文件都需要 path 和非空 content");
        }
    }

    /**
     * 用户会照着卡片里的路径把文件存到自己的项目里，所以路径必须是项目内的相对文件路径：
     * 盘符（C:/）、.. 以及以 / 结尾的目录路径都视为不合法，报错让模型改。
     *
     * <p>反斜杠、重复斜杠和开头的 /（模型常把项目根写成 /src/App.jsx）只是写法差异，
     * 归一化掉即可 —— 这里每一次硬失败都是一次白等的重试，而“说交付了却什么都没给”
     * 正是这张卡要治的病。</p>
     */
    private String sanitizePath(String raw) {
        String normalized = raw.replace('\\', '/').replaceAll("/{2,}", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.length() > MAX_PATH_CHARS
                || normalized.contains(":") || normalized.endsWith("/")) {
            return "";
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) return "";
        }
        return normalized;
    }

    /** 入口文件：模型指定的优先，其次第一个 html（浏览器打开就能看），最后退回第一个文件。 */
    private String pickEntry(ArrayNode files, String requested) {
        String wanted = sanitizePath(requested.trim());
        for (JsonNode file : files) {
            if (file.path("path").asText().equals(wanted)) return wanted;
        }
        for (JsonNode file : files) {
            String path = file.path("path").asText();
            if (path.endsWith(".html") || path.endsWith(".htm")) return path;
        }
        return files.get(0).path("path").asText();
    }

    private String languageOf(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "";
        return LANGUAGES.getOrDefault(path.substring(dot + 1).toLowerCase(Locale.ROOT), "");
    }

    private String limit(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
