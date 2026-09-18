package com.xander.lab.integration.api;

/**
 * 一次站外同步的结果：平台标识、平台侧文章 id、平台侧可访问 URL。
 *
 * <p><b>为什么这里又有一个 {@code PublishResult}。</b>内容域的
 * {@code com.xander.lab.content.api.PublishResult} 字段与它逐字相同
 * （{@code platform} / {@code externalId} / {@code url}），却<b>不能</b>被本模块复用：
 * 按 ADR-2，<b>契约不许依赖契约</b> —— {@code integration-api} 一旦把 {@code content-api}
 * 写进 POM，两个契约就绑成了一个，将来任一方的服务要独立升级都得连带另一方。
 * 所以契约各自带一份等价 DTO，<b>换算落在实现边界</b>（{@code CsdnBlogSyncService} /
 * {@code JuejinBlogSyncService} 里那三行）。这是本仓库第二次这么做
 * （第一次是 {@code blogagent.spi} 的 {@code TokenUsage} / {@code BlogAgentImageUsage}）。</p>
 *
 * <p>⚠️ <b>两份投影必须一起改。</b>字段名或语义动了一边、另一边没动，编译不会报，
 * 而 {@code core-host} 里那条"两份投影在共享字段上相等"的断言会先失败 ——
 * 那是刻意的：这个类型的唯一职责就是跨过部署单元边界后仍然是同一个形状。</p>
 *
 * <p><b>失败不用本类型表达。</b>这里没有错误字段：同步失败靠抛异常，
 * 调用方需要的是中断而不是继续。{@code externalId} 为空（{@code null} 或空串）会被
 * 当作"没有成功过"，实现不要返回空串 —— 内容域据此做幂等。</p>
 *
 * <p>字段改动属于契约改动（V9 §11）：{@code platform} / {@code externalId} / {@code url}
 * 会出现在 HTTP 响应体里，改字段名就是改前端拿到的 JSON。</p>
 */
public record BlogSyncResult(String platform, String externalId, String url) {}
