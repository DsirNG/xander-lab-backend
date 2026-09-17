package com.xander.lab.content.api;

/**
 * 文章状态词表（V9 §11 / §18 / §91）。
 *
 * <h2>为什么进契约</h2>
 * <p>三个值原本是 {@code publishing.application.BlogService} 上的常量。跨部署单元的两个调用方
 * （agent 的 {@code QueryPostsTool} 与 {@code QueryUnsyncedPostsTool}）各自把状态字符串与整数
 * 互转了一遍 —— “draft ⇄ 0”这段映射在两个域里各写了一份。状态词表是内容域的事实，
 * 抄成几份之后改一处就漏几处。</p>
 *
 * <h2>与 {@code BlogService.STATUS_*} 的关系：别名，不是副本</h2>
 * <p>{@code BlogService} 的三个常量现在是本类的<b>别名</b>：</p>
 * <pre>
 * public static final int STATUS_DRAFT = ContentPostStatus.DRAFT;
 * </pre>
 * <p>保留别名而不是删掉，是因为同域的 {@code BlogController}（前端 HTTP 契约）与
 * {@code BlogPlanRunService} 都在用它们，删了会把一次契约抽取变成一次前端可见的改名。
 * 别名仍然是<b>编译期常量</b>（值来自本类的常量表达式），所以 {@code switch} 的
 * {@code case} 标签照旧可用 —— 这一点由 {@code ContentPostStatusTest} 反射验证。</p>
 *
 * <p><b>⚠️ 不要把这三个数字改成枚举。</b>{@code blog_post.status} 是 {@code int} 列，
 * MyBatis 投影与前端响应都按数字走；换枚举会同时改数据库列类型与线上 JSON。
 * 状态机的语义靠这里的 Javadoc 与 {@code BlogService} 的转移规则说明，不靠类型。</p>
 */
public final class ContentPostStatus {

    /** 草稿：作者可见、可编辑，不出现在站点公开列表里。 */
    public static final int DRAFT = 0;

    /** 已发布：出现在站点列表与 RSS 里，可被同步到 CSDN / 掘金。 */
    public static final int PUBLISHED = 1;

    /** 回收站：软删状态。只有本状态的文章允许被彻底删除。 */
    public static final int TRASHED = -1;

    private ContentPostStatus() {
    }
}
