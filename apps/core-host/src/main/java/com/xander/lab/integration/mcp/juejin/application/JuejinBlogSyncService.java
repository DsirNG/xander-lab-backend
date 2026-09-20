package com.xander.lab.integration.mcp.juejin.application;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.PublicationPort;
import com.xander.lab.content.api.PublishCommand;
import com.xander.lab.content.api.PublishResult;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.JuejinBlogSyncPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Synchronizes an existing local blog post to the owner's Juejin account.
 * Juejin requires its own category id and tag ids: the category falls back to the
 * frontend category, and when no explicit tag ids are given the local post's tag
 * names are passed through so the publisher can resolve them to Juejin tag ids.
 *
 * <p><b>为什么 {@code implements} 契约端口。</b>本类有两个<b>跨域</b>调用方，都在别的部署单元：
 * {@code agent} 的 {@code PublishPostTool}（{@code dinqor-agent}）与
 * {@code automation} 的 {@code BlogPlanRunService}（{@code dinqor-content-automation}），
 * 而本类在 {@code dinqor-platform-integration}。拆服务之后它们 import 本类就是
 * "一个服务直接调另一个服务的内部类"，所以它们改成依赖 {@link JuejinBlogSyncPort}。</p>
 *
 * <p>两个消费方接入的代价都只是换个注入类型：端口的方法表与本类的公开方法<b>逐字相同</b>
 * （包括那四个参数），所以这次改造就是加一句 {@code implements} 加一个返回类型的换算。
 * ⚠️ 这也是端口上<b>保留</b> {@code juejinCategoryId} / {@code juejinTagIds} 的原因：
 * 跨域两个消费方目前一律传 {@code (null, null)}，但同域的前端 HTTP 层会传真实值，
 * 而跨进程之后端口是那条路径唯一能穿过的形状 —— 砍掉参数就是让这项能力在拆服务时静默消失。</p>
 *
 * <h2>⚠️ 这里有一次"两份投影"的换算，改动必须成对</h2>
 * <p>{@code content.api.PublishResult} 与 {@link BlogSyncResult} 分量逐字相同却必须是两个类型
 * —— 契约不许依赖契约。换算只有 {@link #toContract} 一处，三个分量<b>逐个点名</b>：
 * 三个都是 String，靠位置写的话调换顺序不会有任何编译错误。
 * {@code JuejinBlogSyncServiceTest} 里有一条断言钉住"两份投影在共享字段上相等"，
 * 与 {@code CsdnBlogSyncService} 里那条是同一个判据的两次落地。</p>
 */
@Service
@RequiredArgsConstructor
public class JuejinBlogSyncService implements JuejinBlogSyncPort {
    private final ContentPostPort contentPostPort;
    private final PublicationPort publicationPort;

    @Override
    public BlogSyncResult sync(Long userId, Long postId, String juejinCategoryId, List<String> juejinTagIds) {
        ContentPost post = contentPostPort.findOwned(userId, postId);
        if (post == null || Integer.valueOf(-1).equals(post.status())) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        // 未显式指定掘金标签 ID 时，复用本地文章的标签（名称），由发布器解析为掘金 tag_id。
        List<String> tags = juejinTagIds != null && !juejinTagIds.isEmpty() ? juejinTagIds : post.tags();
        // taskId 用 -postId：同一篇文章的"本站发布"与"同步到掘金"在 blog_publish_record
        // 里必须落在不同的幂等键上，否则两次发布互相撞键。
        PublishCommand command = new PublishCommand(
                -postId, userId, post.title(), post.summary(), post.content(),
                juejinCategoryId, post.coverImage(), tags);
        return toContract(publicationPort.publish(command, "juejin"));
    }

    /** Looks up a completed Juejin synchronization without issuing another external request. */
    @Override
    public BlogSyncResult findSucceeded(Long userId, Long postId) {
        return postId == null ? null : toContract(publicationPort.findSucceeded(userId, -postId, "juejin"));
    }

    /**
     * 内容域的结果 → 契约的结果。
     *
     * <p>三个分量逐个点名：它们都是 {@code String}，靠位置写的话
     * {@code platform} 与 {@code url} 对调不会有任何编译错误。
     * {@code null} 原样传过去 —— 端口用 {@code null} 表示"没有成功记录"。</p>
     */
    private static BlogSyncResult toContract(PublishResult result) {
        return result == null ? null
                : new BlogSyncResult(result.platform(), result.externalId(), result.url());
    }
}
