package com.xander.lab.blogagent.spi;

/**
 * 一次博客生成任务的只读视图，供跨域调用方使用。
 *
 * <h2>为什么是收窄而不是镜像</h2>
 * <p>域实体 {@code blogagent.domain.BlogAgentTask} 有 25 个字段，其中 4 个被跨域读取：</p>
 * <table>
 *   <caption>消费方读取点（BlogGenerationTool.outcome）</caption>
 *   <tr><th>分量</th><th>用途</th></tr>
 *   <tr><td>{@code id}</td><td>工具输出里的 {@code taskId}，后续 publish 要用</td></tr>
 *   <tr><td>{@code title}</td><td>工具输出的标题</td></tr>
 *   <tr><td>{@code summary}</td><td>工具输出的摘要</td></tr>
 *   <tr><td>{@code publishedPostId}</td><td>工具输出的 {@code publishedPostId}，未发布时为 {@code null}</td></tr>
 * </table>
 *
 * <p>不带的 21 个里，{@code content} / {@code outline} / {@code review} / {@code tagsJson}
 * 等是流水线中间产物，{@code userId} / {@code status} / {@code stage} / {@code errorMessage}
 * 是域内状态机，跨域这一侧都不读。**镜像它意味着让契约跟着实体的列走**，
 * 而实体加一列就会顺带改契约 —— 收窄是对这个耦合的拒绝。</p>
 *
 * <p>⚠️ {@code title} 与 {@code summary} 是相邻的 {@code String}，写反不会有编译错误，
 * 只会让工具把摘要当标题报给用户。分量顺序由 {@code RecordShapeTest} 钉住。</p>
 *
 * @param id              任务 id
 * @param title           文章标题，未生成完时为 {@code null}
 * @param summary         文章摘要，未生成完时为 {@code null}
 * @param publishedPostId 已发布的本地文章 id，未发布时为 {@code null}
 */
public record BlogAgentTaskView(Long id, String title, String summary, Long publishedPostId) {
}
