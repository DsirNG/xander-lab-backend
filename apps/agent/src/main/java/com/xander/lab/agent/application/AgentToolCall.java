package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模型在一步里点名的一次工具调用。
 *
 * <p>一步可以有多个：模型把互不依赖的查询一起报出来，服务端在一次模型调用里把它们
 * 全部执行完，而不是让模型分三步、每次只问一件事——那会把 {@code maxSteps} 白白烧在
 * 往返上。参数在这里已经是**解析过的 JSON 对象**（模型按协议交的是字符串，
 * 由 {@code AgentModelClient.normalizeDecisionArgs} 解码），编排层不再做二次解析。</p>
 *
 * @param toolName 工具名，必定在当轮允许集合里
 * @param args     工具参数对象；协议要求对象，所以不会是 null
 */
public record AgentToolCall(String toolName, JsonNode args) {
}
