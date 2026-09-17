package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.PublicationPort;
import com.xander.lab.content.api.PublishCommand;
import com.xander.lab.content.api.PublishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Synchronizes an existing local blog post to the owner's CSDN account. */
@Service
@RequiredArgsConstructor
public class CsdnBlogSyncService {
    private final ContentPostPort contentPostPort;
    private final PublicationPort publicationPort;

    public PublishResult sync(Long userId, Long postId) {
        ContentPost post = contentPostPort.findOwned(userId, postId);
        if (post == null || Integer.valueOf(-1).equals(post.status())) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        PublishCommand command = new PublishCommand(
                -postId, userId, post.title(), post.summary(), post.content(),
                post.categoryName(), post.coverImage(), post.tags());
        return publicationPort.publish(command, "csdn");
    }

    /** Looks up a completed CSDN synchronization without issuing another external request. */
    public PublishResult findSucceeded(Long userId, Long postId) {
        return postId == null ? null : publicationPort.findSucceeded(userId, -postId, "csdn");
    }
}
