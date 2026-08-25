package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentMemory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Memory 读取端：按关键词翻查长期记忆。
 *
 * <p>每轮已自动注入最相关的若干条，所以本工具只用于自动注入没覆盖到的情况——
 * 用户追问一件很久以前提过的具体事实，或需要确认某条记忆是否存在。</p>
 */
@Component
public class RecallMemoryTool implements AgentTool {

    private final AgentMemoryService memoryService;

    public RecallMemoryTool(AgentMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "recall_memory";
    }

    @Override
    public String description() {
        return "按关键词翻查你自己的长期记忆。"
                + "注意：每一轮对话已经自动注入了最相关的记忆，所以只有在自动注入没覆盖到时才调用本工具——"
                + "例如用户追问很久以前提过的某个具体事实，或你需要确认某条记忆是否真的存在。"
                + "不要为了写入而先调用本工具，写入直接用 remember。"
                + "返回结果为空时，如实说明没有记录，不要凭印象编造。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query")
                .put("type", "string")
                .put("description", "要查找的关键词或主题；留空则返回最近更新的记忆");
        properties.putObject("limit")
                .put("type", "integer")
                .put("description", "最多返回多少条，默认 " + AgentMemoryService.RECALL_LIMIT);
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        int limit = args.path("limit").asInt(AgentMemoryService.RECALL_LIMIT);
        List<AgentMemory> memories = memoryService.recall(userId, args.path("query").asText(""), limit);

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("matched", memories.size());
        ArrayNode records = outcome.putArray("items");
        memories.forEach(memory -> {
            ObjectNode record = records.addObject();
            record.put("memoryKey", memory.getMemoryKey());
            record.put("kind", memory.getKind());
            record.put("content", memory.getContent());
            record.put("updatedAt", memory.getUpdatedAt() == null ? "" : memory.getUpdatedAt().toString());
        });
        outcome.put("hint", memories.isEmpty()
                ? "长期记忆里没有相关记录；如实告知用户，并可以询问是否要现在记下来。"
                : "以上是你对该用户的长期记忆，可直接用于回答。");
        return outcome;
    }
}
