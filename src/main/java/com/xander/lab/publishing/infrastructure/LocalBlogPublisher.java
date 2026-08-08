package com.xander.lab.publishing.infrastructure;

import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import org.springframework.stereotype.Component;

@Component
public class LocalBlogPublisher implements BlogPublisher {
    private final BlogService blogService;
    public LocalBlogPublisher(BlogService blogService) { this.blogService = blogService; }
    @Override public String platform() { return "local"; }
    @Override public PublishResult publish(PublishCommand command) {
        BlogPostDTO post = new BlogPostDTO();
        post.setTitle(command.title()); post.setSummary(command.summary());
        post.setContent(command.content()); post.setCategoryId(command.categoryId());
        post.setCoverImage(command.coverImage());
        post.setTags(command.tags()); post.setPublish(true);
        BlogPostVO result = blogService.createBlog(post, true, "agent-task-" + command.taskId());
        return new PublishResult("local", String.valueOf(result.getId()), "/blog/" + result.getId());
    }
}
