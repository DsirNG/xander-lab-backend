package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.PublicationPort;
import com.xander.lab.content.api.PublishCommand;
import com.xander.lab.content.api.PublishResult;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.CsdnBlogSyncPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Synchronizes an existing local blog post to the owner's CSDN account.
 *
 * <p><b>为什么 {@code implements} 契约端口。</b>跨域调用方是 {@code agent} 的
 * {@code PublishPostTool}（{@code dinqor-agent}），本类在 {@code dinqor-platform-integration}。
 * 拆服务之后它 import 本类就是"一个服务直接调另一个服务的内部类"，所以它改成依赖
 * {@link CsdnBlogSyncPort}。方法表与端口<b>逐字相同</b>，所以这次改造就是加一句
 * {@code implements} 加一个返回类型的换算 —— 没有新增按平台分派的中间层，
 * 也就没有"分派写错只在运行时才炸"的新缝。</p>
 *
 * <p>同域的三个消费方（{@code CsdnBlogSyncController} / {@code CsdnMcpApplicationService}）
 * 仍然注入本具体类：它们与本类同在 {@code dinqor-platform-integration}，拆完还在一个进程里。
 * 它们只跟着改了返回类型（{@code PublishResult} → {@link BlogSyncResult}），
 * 而两个记录的分量名逐字相同，所以 HTTP 响应体的 JSON <b>一个字段都没变</b>。</p>
 *
 * <h2>⚠️ 这里有一次"两份投影"的换算，改动必须成对</h2>
 * <p>{@code content.api.PublishResult} 与 {@link BlogSyncResult} 分量逐字相同却必须是两个类型
 * —— 契约不许依赖契约（ADR-2），{@code integration-api} 不能把 {@code content-api} 写进 POM。
 * 换算只有 {@link #toContract} 一处，三个分量<b>逐个点名</b>而不是靠位置：
 * 三个都是 String，调换顺序编译器不会有任何意见。
 * {@code CsdnBlogSyncServiceTest} 里有一条断言钉住"两份投影在共享字段上相等"。</p>
 */
@Service
@RequiredArgsConstructor
public class CsdnBlogSyncService implements CsdnBlogSyncPort {
    private final ContentPostPort contentPostPort;
    private final PublicationPort publicationPort;

    @Override
    public BlogSyncResult sync(Long userId, Long postId) {
        ContentPost post = contentPostPort.findOwned(userId, postId);
        if (post == null || Integer.valueOf(-1).equals(post.status())) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        // taskId 用 -postId：同一篇文章的"本站发布"与"同步到 CSDN"在 blog_publish_record
        // 里必须落在不同的幂等键上，否则两次发布互相撞键。
        PublishCommand command = new PublishCommand(
                -postId, userId, post.title(), post.summary(), post.content(),
                post.categoryName(), post.coverImage(), post.tags());
        return toContract(publicationPort.publish(command, "csdn"));
    }

    /** Looks up a completed CSDN synchronization without issuing another external request. */
    @Override
    public BlogSyncResult findSucceeded(Long userId, Long postId) {
        return postId == null ? null : toContract(publicationPort.findSucceeded(userId, -postId, "csdn"));
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
