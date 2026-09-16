package com.xander.lab.content.api;

/**
 * 一次发布的结果：外部平台的标识、外部文章 id 与可访问 URL。
 *
 * <p>三个字段全部来自外部平台的响应，内容域把它们原样写进 {@code BlogPublishRecord}
 * （{@code externalId} / {@code url}）并据此做幂等：同一 {@code taskId + platform}
 * 再次发布时，若已有 SUCCEEDED 记录且 {@code externalId} 非空，就直接返回本记录而不再请求平台。
 * 所以 {@code externalId} 为空会被当作"没有成功过"，实现不要返回空串。</p>
 *
 * <p><b>失败不用本类型表达。</b>这里没有错误字段，失败靠抛异常，见 {@link BlogPublisher}。</p>
 */
public record PublishResult(String platform, String externalId, String url) {}
