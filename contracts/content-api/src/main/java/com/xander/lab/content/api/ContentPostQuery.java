package com.xander.lab.content.api;

/**
 * 查询自己文章时的筛选条件。
 *
 * <h2>为什么抽成 DTO 而不是继续传位置参数</h2>
 * <p>{@code BlogService.listMyPosts(Integer, String, Boolean, Boolean, Integer)}
 * 中间有两个相邻的 {@code Boolean}：{@code juejinSynced} 与 {@code csdnSynced}。
 * 写反不会有任何编译错误 —— “哪些文章还没同步到掘金”会静默变成“哪些还没同步到 CSDN”，
 * 而两条 SQL 都返回非空结果，测试不专门覆盖就发现不了。具名分量让写错名字编译期就红。</p>
 *
 * <p>分量顺序仍然是位置契约（跨进程序列化时按顺序对齐），由
 * {@code ContentPostQueryTest} 反射钉住。</p>
 *
 * <h2>⚠️ 分页读取只认 {@code status} 与 {@code search}</h2>
 * <p>{@link ContentPostPort#listMyPosts} 走的是分页 SQL
 * （{@code BlogPostMapper.selectMyPostList}），它<b>只</b>支持状态与关键词过滤。
 * 这是{@code BlogService.getMyBlogs} 就有的行为，不是新引入的限制 ——
 * 但旧签名根本不接受同步状态参数，所以“传了不生效”这件事没法表达；
 * 现在 {@code query} 能表达，于是 {@link ContentPostPort#listMyPosts} 选择
 * <b>直接拒绝</b>而不是静默忽略。全量读取 {@link ContentPostPort#listAllMyPosts}
 * 支持全部四个条件。</p>
 *
 * @param status        状态过滤，取值见 {@link ContentPostStatus}；{@code null} 表示不限
 * @param search        标题或摘要关键词；{@code null} / 空白表示不限
 * @param juejinSynced  {@code true} 只看已成功同步到掘金的、{@code false} 只看未同步的、
 *                      {@code null} 不限；<b>仅 {@code listAllMyPosts} 生效</b>
 * @param csdnSynced    {@code true} 只看已成功同步到 CSDN 的、{@code false} 只看未同步的、
 *                      {@code null} 不限；<b>仅 {@code listAllMyPosts} 生效</b>
 */
public record ContentPostQuery(
        Integer status,
        String search,
        Boolean juejinSynced,
        Boolean csdnSynced) {

    /** 全不限的查询条件。 */
    public static final ContentPostQuery NONE = new ContentPostQuery(null, null, null, null);
}
