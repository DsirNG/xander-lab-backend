package com.xander.lab.publishing.application;

import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import com.xander.lab.service.BlogService;
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
                post.getCategoryName(), post.getTags());
        return publicationService.publish(command, "csdn");
    }
}
