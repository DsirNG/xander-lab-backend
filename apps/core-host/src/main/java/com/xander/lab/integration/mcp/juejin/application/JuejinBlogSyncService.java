package com.xander.lab.integration.mcp.juejin.application;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.PublicationPort;
import com.xander.lab.content.api.PublishCommand;
import com.xander.lab.content.api.PublishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Synchronizes an existing local blog post to the owner's Juejin account.
 * Juejin requires its own category id and tag ids: the category falls back to the
 * frontend category, and when no explicit tag ids are given the local post's tag
 * names are passed through so the publisher can resolve them to Juejin tag ids.
 */
@Service
@RequiredArgsConstructor
public class JuejinBlogSyncService {
    private final ContentPostPort contentPostPort;
    private final PublicationPort publicationPort;

    public PublishResult sync(Long userId, Long postId, String juejinCategoryId, List<String> juejinTagIds) {
        ContentPost post = contentPostPort.findOwned(userId, postId);
        if (post == null || Integer.valueOf(-1).equals(post.status())) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        // 未显式指定掘金标签 ID 时，复用本地文章的标签（名称），由发布器解析为掘金 tag_id。
        List<String> tags = juejinTagIds != null && !juejinTagIds.isEmpty() ? juejinTagIds : post.tags();
        PublishCommand command = new PublishCommand(
                -postId, userId, post.title(), post.summary(), post.content(),
                juejinCategoryId, post.coverImage(), tags);
        return publicationPort.publish(command, "juejin");
    }

    /** Looks up a completed Juejin synchronization without issuing another external request. */
    public PublishResult findSucceeded(Long userId, Long postId) {
        return postId == null ? null : publicationPort.findSucceeded(userId, -postId, "juejin");
    }
}
