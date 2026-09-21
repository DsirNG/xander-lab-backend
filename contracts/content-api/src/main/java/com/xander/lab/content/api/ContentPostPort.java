package com.xander.lab.content.api;

import java.util.List;

/**
 * 内容域对外公开的“读写我的文章”端口。
 *
 * <h2>为什么这个接口必须住在中立模块</h2>
 * <p>它由 {@code publishing.application.BlogService} 实现，而调用方分散在<b>三个部署单元</b>：
 * {@code agent}（{@code dinqor-agent}）、{@code integration}
 * （{@code dinqor-platform-integration}）、{@code profile}（{@code dinqor-core-host}）。
 * 拆服务之后，让它们各自 import 内容域的具体 {@code @Service} 就是“一个服务直接调另一个服务的
 * 内部类”。</p>
 *
 * <h2>为什么是抽端口，不是把 {@code BlogService} 搬过来</h2>
 * <p>{@code BlogService} 是 Core 兼容实现，Content-Automation 的
 * {@code ContentAgentPostService} 是 Agent 内网边界实现：两者都是具体实现，
 * 前者保留 {@code @Service} + {@code @RequiredArgsConstructor} 的旧形态，后者直接落 Content 数据，
 * 通过 Content 自己的 JDBC 读写服务与 {@code StringRedisTemplate} 访问数据。搬进契约等于把
 * JDBC 与 Redis 拖进每个消费者，
 * 那会把它拖进 spring-webmvc，启动就失败。所以只在契约里描述接口，实现留在内容域。</p>
 *
 * <h2>⚠️ 为什么每个方法都要显式收 {@code userId}</h2>
 * <p>{@code BlogService} 有 7 个方法内部读 {@code auth.core.UserContext}
 * （一个 {@code ThreadLocal}），由调用方在调用前 {@code setUserId} 来传递身份。
 * 同进程里这能工作，但<b>跨进程根本没有 ThreadLocal 可传</b> —— 端口必须把身份变成参数，
 * 否则这个端口只是在描述一个无法跨进程的调用。</p>
 *
 * <p>实现侧的旧公开方法<b>签名不变</b>，仍然是“读 ThreadLocal 再转调”的薄壳，
 * 所以 {@code BlogController} 的 17 个调用点与同域的 {@code LocalBlogPublisher} /
 * {@code BlogPlanRunService} / {@code BlogAgentService} 一行都不用改。
 * 这是 {@code MediaAssetPort} 用过的同一手法（同域便捷入口 + 跨域端口，两者共用一个私有实现体）。</p>
 *
 * <h2>失败语义（跨进程后仍然成立，实现必须遵守）</h2>
 * <ul>
 *   <li>{@code userId} 为 {@code null} → 抛 {@link IllegalArgumentException}（“未登录或登录已过期”）。
 *       这里抛的就是它，不是 {@code IllegalStateException} —— 换异常类型会改 HTTP 状态码。</li>
 *   <li>{@link #findOwned} 查不到、<b>或存在但不属于该用户</b>时返回 {@code null}，不抛异常。
 *       调用方拿它做“存在性判断”，抛异常会把正常流程变成异常路径。</li>
 *   <li>{@link #listMyPosts} 只认 {@code status} / {@code search}；若 {@code query} 里带了
 *       同步状态过滤，抛 {@link IllegalArgumentException} 而不是静默忽略。
 *       理由见 {@link ContentPostQuery}。</li>
 *   <li>{@link #create} 在 {@code input.title()} / {@code input.content()} 为空时抛
 *       {@link IllegalArgumentException}；{@link #update} 在 {@code input} 的六个分量全为
 *       {@code null} 时同样抛。</li>
 *   <li>{@link #update} 与 {@link #moveToTrash} 对不属于 {@code userId} 的文章抛
 *       {@link IllegalArgumentException}（“无权操作该文章”）。</li>
 *   <li>全部异常都是<b>非受检</b>的。契约不声明 {@code throws}，跨进程时由适配器决定
 *       怎么映射成 HTTP 状态。</li>
 * </ul>
 *
 * <h2>刻意不在端口上的 16 个方法</h2>
 * <p>{@code BlogService} 有 23 个公开方法，这里只放<b>被别的部署单元真正调到</b>的 7 个。
 * 留在实现类上的包括：{@code getManageStats} / {@code getWeeklyPublishRhythm} /
 * {@code getBlogs} / {@code getRecentBlogs} / {@code getCategories} / {@code getAllTags} /
 * {@code getPopularTags} / {@code recordView} / {@code permanentlyDeleteBlog} /
 * {@code getPublishedPostId} / {@code updateBlogFromDto}（以上只有同域的
 * {@code BlogController} 在用），以及 {@code getTitlesByIds} / {@code updateBlogStatus}
 * （只有 {@code automation} 在用）、{@code getBlogById}（只有 {@code blogagent} 在用）、
 * {@code createBlog(dto)} 与 {@code createBlog(dto, publish, requestId)}。</p>
 *
 * <p><b>规则是“跨部署单元才算数”，不是“看起来像对外接口就放进来”。</b>
 * {@code automation} / {@code blogagent} 与内容域同在 {@code dinqor-content-automation}，
 * 拆完还在一个进程里，把它们的调用面也搬上端口只会让端口变宽而耦合没变 ——
 * 那正是这个端口存在的意义被稀释的方式。</p>
 *
 * <p><b>⚠️ 端口宽度没有任何东西在守。</b>加一个 {@code default} 方法不会编译报错、不会动任何指标、
 * 门禁也不查 —— 于是“文章读写端口”会慢慢长成所有人绕过内容域边界的新入口。
 * 方法个数与每个方法的<b>完整签名</b>由 {@code ContentPostPortTest} 用反射钉住。
 * 要加方法就显式改那个测试，让它成为一次有意识的契约扩展。</p>
 *
 * @see PublicationPort
 */
public interface ContentPostPort {

    /**
     * 分页读取 {@code userId} 自己的文章。
     *
     * <p>走分页 SQL，因此只支持 {@code status} 与 {@code search} 两个条件 ——
     * 见 {@link ContentPostQuery} 里“分页读取只认 status 与 search”。</p>
     *
     * @param page 页码，从 1 开始；小于 1 时按 1 处理
     * @param size 每页大小；小于 1 时按 10 处理，上限 50（夹取规则）
     */
    ContentPostPage listMyPosts(Long userId, ContentPostQuery query, int page, int size);

    /**
     * 一次取回 {@code userId} 的全部文章，不分页。
     *
     * <p>给智能体用：它要按同步状态筛出“哪些还没同步到掘金/CSDN”，逐页聚合没有意义，
     * 而且分页 SQL 也不支持这个条件。四个筛选条件全部生效。</p>
     *
     * @param limit 非 {@code null} 时只返回最新前 N 条；{@code null} 表示不限
     */
    List<ContentPost> listAllMyPosts(Long userId, ContentPostQuery query, Integer limit);

    /**
     * 取一篇文章，<b>且必须属于 {@code userId}</b>。
     *
     * <p>归属过滤在实现里做（SQL 层比对 {@code user_id}），不能只靠调用方传对 userId ——
     * 这是越权读取的唯一防线。回收站里的文章也能取到（同步流程需要读它来判断“已删除，不同步”）。</p>
     *
     * @return 不存在、或存在但不属于该用户时为 {@code null}
     */
    ContentPost findOwned(Long userId, Long postId);

    /**
     * 统计 {@code userId} 当前可管理的文章数，<b>不含回收站</b>。
     *
     * <p>调用方是个人主页概览。返回 {@code long} 而不是 {@code Long}：计数没有“未知”这一态，
     * 用可空类型会逼调用方写一个永远不会命中的分支。</p>
     */
    long countManaged(Long userId);

    /**
     * 新建一篇文章。
     *
     * <p>作者名由内容域解析（{@code userLookupPort}），<b>不接受调用方传入</b> ——
     * 否则调用方可以伪造作者。阅读时间同理，由正文长度算出。</p>
     *
     * @param publish {@code true} 直接发布（状态 {@link ContentPostStatus#PUBLISHED}），
     *                {@code false} 建为草稿
     * @return 新建后的完整文章；实现方在写入后重新读一次，因此 {@code tags} 已填充
     */
    ContentPost create(Long userId, ContentPostInput input, boolean publish);

    /**
     * 更新一篇文章，<b>只改 {@code input} 里非 {@code null} 的分量</b>。
     *
     * <p>{@code input.tags()} 非 {@code null} 时<b>整体替换</b>该文章的全部标签。</p>
     *
     * @return 更新后的完整文章
     */
    ContentPost update(Long userId, Long postId, ContentPostInput input);

    /**
     * 把文章移进回收站（软删，状态置 {@link ContentPostStatus#TRASHED}），可恢复。
     *
     * <p><b>⚠️ 名字刻意叫 {@code moveToTrash} 而不是 {@code delete}。</b>
     * 跨部署单元的调用方是 MCP 的 {@code delete_post} 工具，它的 description 写的是
     * “Permanently deletes a blog post” —— 而它实际调的就是这个软删。
     * 这是<b>文档与行为不一致</b>（工具描述骗了 AI 客户端），
     * 只把行为照实写进名字，没有改行为、也没有改那句 description。
     * 要修就得单独修，并想清楚 {@code permanentlyDeleteBlog} 要求前态必须是回收站这一点。</p>
     */
    void moveToTrash(Long userId, Long postId);
}
