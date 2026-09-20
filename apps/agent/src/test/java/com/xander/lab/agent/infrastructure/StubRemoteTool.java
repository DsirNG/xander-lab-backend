package com.xander.lab.agent.infrastructure;

import com.xander.lab.agent.spi.RemoteTool;
import com.xander.lab.agent.spi.RemoteToolCall;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试替身：一个可观察的 {@link RemoteTool}。
 *
 * <p>两个测试类（{@code RemoteToolAdapterTest} / {@code RemoteToolDynamicSourceTest}）都要它。
 * 不写成 lambda 是因为契约刻意没有 {@code default} 方法（见 {@code RemoteToolTest}），
 * 七个方法一个都不能省——这也正是"端口变宽会被立刻发现"的另一面。</p>
 */
final class StubRemoteTool implements RemoteTool {

    String name = "mcp__platform__ping";
    String description = "探活";
    String schemaJson = "{\"type\":\"object\",\"properties\":{}}";
    String prompt = "";
    String approval = "";
    String result = "{\"ok\":true}";
    boolean readOnly;
    String progressMessage = "正在调用";

    /** schema 被读了几次——用来钉住"解析一次并缓存"。 */
    final AtomicInteger schemaReads = new AtomicInteger();

    final AtomicInteger invocations = new AtomicInteger();
    Long invokedUserId;
    String invokedArgsJson;
    RemoteToolCall invokedCall;
    String approvalArgsJson;

    StubRemoteTool() {
    }

    StubRemoteTool(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String parametersSchemaJson() {
        schemaReads.incrementAndGet();
        return schemaJson;
    }

    @Override
    public String promptText() {
        return prompt;
    }

    @Override
    public String approvalReason(String argsJson) {
        approvalArgsJson = argsJson;
        return approval;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public String invoke(Long userId, String argsJson, RemoteToolCall call) {
        invocations.incrementAndGet();
        invokedUserId = userId;
        invokedArgsJson = argsJson;
        invokedCall = call;
        // 按契约该做的事：先报一条进度，再查一次取消。测试靠这两次调用把适配器的桥接层驱动起来
        // ——不驱动的话，"进度与取消能不能穿过桥"这件事根本没被测到。
        call.onProgress(progressMessage);
        call.checkCancellation();
        return result;
    }
}
