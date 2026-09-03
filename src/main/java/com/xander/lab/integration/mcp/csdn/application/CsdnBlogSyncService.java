package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import com.xander.lab.publishing.application.PublicationService;
import com.xander.lab.publishing.application.BlogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Synchronizes an existing local blog post to the owner's CSDN account. */
@Service
@RequiredArgsConstructor
public class CsdnBlogSyncService {
    private final BlogService blogService;
    private final PublicationService publicationService;

    public PublishResult sync(Long userId, Long postId) {
        BlogPostVO post = blogService.getManagedBlogById(userId, postId);
        if (post == null || Integer.valueOf(-1).equals(post.getStatus())) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        PublishCommand command = new PublishCommand(
                -postId, userId, post.getTitle(), post.getSummary(), post.getContent(),
                post.getCategoryName(), post.getCoverImage(), post.getTags());
        return publicationService.publish(command, "csdn");
    }

    /** Looks up a completed CSDN synchronization without issuing another external request. */
    public PublishResult findSucceeded(Long userId, Long postId) {
        return postId == null ? null : publicationService.findSucceeded(userId, -postId, "csdn");
    }
}
