package com.xander.lab.integration.wechat.application;

import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.publishing.application.PublicationService;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Synchronizes one owned local article to the configured user's WeChat draft box. */
@Service
@RequiredArgsConstructor
public class WechatBlogSyncService {
    private final BlogService blogService;
    private final PublicationService publicationService;

    public PublishResult sync(Long userId, Long postId) {
        BlogPostVO post = blogService.getManagedBlogById(userId, postId);
        if (post == null || Integer.valueOf(-1).equals(post.getStatus())) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        PublishCommand command = new PublishCommand(
                -postId, userId, post.getTitle(), post.getSummary(), post.getContent(),
                post.getCategory(), post.getCoverImage(), post.getTags());
        return publicationService.publish(command, "wechat");
    }

    /** Looks up an existing draft sync without contacting WeChat again. */
    public PublishResult findSucceeded(Long userId, Long postId) {
        return postId == null ? null : publicationService.findSucceeded(userId, -postId, "wechat");
    }
}
