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
 * Juejin requires its own category id and tag ids, so both are accepted explicitly;
 * the publisher falls back to the frontend category when the category id is absent.
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
        PublishCommand command = new PublishCommand(
                -postId, userId, post.getTitle(), post.getSummary(), post.getContent(),
                juejinCategoryId, post.getCoverImage(), juejinTagIds);
        return publicationService.publish(command, "juejin");
    }
}