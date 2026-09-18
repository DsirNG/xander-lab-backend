package com.xander.lab.blogagent.spi;

/**
 * 创建一次博客生成任务的入参。
 *
 * <p>域侧的 {@code blogagent.api.BlogAgentTaskCreateRequest} 是 {@code @Data} 可变类，
 * 带 {@code @NotBlank} / {@code @Size}，那些注解只在 {@code BlogAgentController} 标了
 * {@code @Valid} 时才生效。按 ADR-1 契约不许带框架类型，所以这里是一份**无注解的等价物** ——
 * 两个分量逐字段同名同序，转换是一次直接映射，没有任何取舍。</p>
 *
 * <p>⚠️ 两个分量都是 {@code String}，而且是相邻的。记录靠位置构造，写反不会有编译错误：
 * 把主题当成幂等键、把幂等键当成主题。症状是"任务内容变成了那串 invocation id"，
 * 而且只有真正跑一次模型调用才会显现。分量名与顺序由 {@code RecordShapeTest} 钉住，
 * 域侧 VO → 记录的那一行由应用模块的映射测试用**互不相同的字面量**钉住。</p>
 *
 * @param input           用户想写的主题、经历或项目描述的完整原文；必填
 * @param clientRequestId 调用方给的稳定幂等键，可为 {@code null}；同用户同键只会建一个任务
 */
public record BlogAgentTaskDraft(String input, String clientRequestId) {
}
