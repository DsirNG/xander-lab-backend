package com.xander.lab.integration.mcp.juejin.application;

import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.publishing.application.PublicationService;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
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
    private final BlogService blogService;
    private final PublicationService publicationService;

    public PublishResult sync(Long userId, Long postId, String juejinCategoryId, List<String> juejinTagIds) {
        BlogPostVO post = blogService.getManagedBlogById(userId, postId);
        if (post == null || Integer.valueOf(-1).equals(post.getStatus())) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        // 未显式指定掘金标签 ID 时，复用本地文章的标签（名称），由发布器解析为掘金 tag_id。
        List<String> tags = juejinTagIds != null && !juejinTagIds.isEmpty() ? juejinTagIds : post.getTags();
        PublishCommand command = new PublishCommand(
                -postId, userId, post.getTitle(), post.getSummary(), post.getContent(),
                juejinCategoryId, post.getCoverImage(), tags);
        return publicationService.publish(command, "juejin");
    }
}