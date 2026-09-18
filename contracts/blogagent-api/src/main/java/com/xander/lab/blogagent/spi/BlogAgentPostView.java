package com.xander.lab.blogagent.spi;

/**
 * 本地文章在"已发布"这一面上的只读视图。
 *
 * <h2>为什么需要它</h2>
 * <p>域侧的 {@code BlogAgentService.publish} / {@code findPublishedPost} 返回的是
 * {@code publishing.api.BlogPostVO} —— 那是**内容域的前端 HTTP 类型**（17 个字段）。跨域调用方
 * 读它就意味着 agent 必须同时依赖 blogagent 的实现类和 publishing 的 api 层，两跳都跨部署单元。
 * 这个记录把那 17 个字段收窄成调用方真正读的 5 个。</p>
 *
 * <table>
 *   <caption>消费方读取点（PublishPostTool 的两条分支）</caption>
 *   <tr><th>分量</th><th>用途</th></tr>
 *   <tr><td>{@code id}</td><td>工具输出里的 {@code postId}，也是同步到平台时的文章标识</td></tr>
 *   <tr><td>{@code title}</td><td>工具输出的标题</td></tr>
 *   <tr><td>{@code status}</td><td>判断本站是否已发布（{@code == 1}），reconcile 分支靠它</td></tr>
 *   <tr><td>{@code csdnSynced}</td><td>CSDN 幂等：已同步过就不再推一次</td></tr>
 *   <tr><td>{@code juejinSynced}</td><td>掘金幂等</td></tr>
 * </table>
 *
 * <h2>⚠️ 两个相邻的 Boolean 是位置契约里最危险的一处</h2>
 * <p>{@code csdnSynced} 与 {@code juejinSynced} 相邻、同类型，写反不会有编译错误。
 * 症状不是崩溃，而是**对着掘金的状态判 CSDN 的幂等** —— 该同步的不同步，不该同步的跳过。</p>
 *
 * <p>更麻烦的是：{@code Boolean} 只有两个取值，**一组字面量钉不满**。
 * 若只断言一次 {@code (true, false)}，首尾对调后仍会全绿。所以
 * {@code BlogAgentPortMappingTest} 用**两组互补**的字面量
 * （{@code (true, false)} 与 {@code (false, true)}）各钉一次。
 * 一般规则：n 个相邻同类型分量需要 ⌈log₂(n+1)⌉ 组互补字面量；{@code String} / {@code long}
 * 可以全不同，一组就够，{@code Boolean} 是那个特例。</p>
 *
 * <p>{@code status} 是装箱 {@code Integer} 而不是 {@code int}：域侧 {@code BlogPostVO.status}
 * 可以为 {@code null}，而调用方的判据是 {@code status == null || status != 1}（"未发布"与
 * "状态未知"在这里是同一件事，但**记录必须如实保留 {@code null}**，
 * 不能用一个默认值把"没传"变成"传了 0"）。</p>
 *
 * @param id           本地文章 id
 * @param title        文章标题
 * @param status       本地发布状态，{@code null} 表示未知
 * @param csdnSynced   是否已同步过 CSDN，{@code null} 表示未知
 * @param juejinSynced 是否已同步过掘金，{@code null} 表示未知
 */
public record BlogAgentPostView(Long id, String title, Integer status,
                                Boolean csdnSynced, Boolean juejinSynced) {
}
