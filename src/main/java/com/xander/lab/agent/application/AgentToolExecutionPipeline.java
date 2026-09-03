package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * The only execution boundary for model-requested tools in a conversation run.
 *
 * <p>Visibility and argument checks happen before an invocation is persisted. The stable invocation
 * identity is then durable before the tool starts. Approval, idempotency reconciliation and timeout
 * policies extend this pipeline rather than being implemented by individual skills.</p>
 */
@Service
public class AgentToolExecutionPipeline {
    private final AgentToolRegistry toolRegistry;
    private final AgentExecutionJournal executionJournal;

    public AgentToolExecutionPipeline(AgentToolRegistry toolRegistry, AgentExecutionJournal executionJournal) {
        this.toolRegistry = toolRegistry;
        this.executionJournal = executionJournal;
    }

    /** Compatibility factory for focused legacy loop tests without journal persistence. */
    static AgentToolExecutionPipeline legacy(AgentToolRegistry toolRegistry) {
        return new AgentToolExecutionPipeline(toolRegistry, AgentExecutionJournal.disabled());
    }

    /** Validates capability and basic schema requirements before a durable tool invocation begins. */
    public ExecutedToolInvocation execute(AgentStep step, Long userId, Long conversationId, long runVersion,
                                          Set<String> allowedTools, String toolName, JsonNode args,
                                          AgentTool.ProgressListener progress, Consumer<String> onStarted) {
        if (toolName == null || !allowedTools.contains(toolName)) {
            throw new IllegalArgumentException("当前 Skill 不允许调用工具 " + safeToolName(toolName));
        }
        AgentTool tool = toolRegistry.get(toolName);
        if (tool == null) throw new IllegalArgumentException("未知工具 " + safeToolName(toolName));
        validateArguments(tool, args);
        progress.checkCancellation();

        String invocationId = UUID.randomUUID().toString();
        AgentToolInvocation invocation = executionJournal.startInvocation(step, invocationId, toolName, args);
        onStarted.accept(invocationId);
        try {
            JsonNode result = tool.execute(new AgentToolContext(userId, conversationId, runVersion, invocationId),
                    args, progress);
            executionJournal.completeInvocation(invocation, result);
            return new ExecutedToolInvocation(invocationId, result);
        } catch (CancellationException exception) {
            executionJournal.cancelInvocation(invocation);
            throw exception;
        } catch (Exception exception) {
            executionJournal.failInvocation(invocation, safeMessage(exception));
            throw exception;
        }
    }

    private void validateArguments(AgentTool tool, JsonNode args) {
        if (args == null || !args.isObject()) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        JsonNode required = tool.parametersSchema().path("required");
        for (JsonNode field : required) {
            String name = field.asText("");
            if (!name.isBlank() && (!args.has(name) || args.path(name).isNull())) {
                throw new IllegalArgumentException("工具参数缺少 " + name);
            }
        }
    }

    private String safeToolName(String toolName) {
        return toolName == null || toolName.isBlank() ? "（空工具名）" : toolName;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "工具执行失败";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
