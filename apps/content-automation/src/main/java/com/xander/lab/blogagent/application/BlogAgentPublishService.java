package com.xander.lab.blogagent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.ContentAgentPostService;
import com.xander.lab.publishing.application.BlogPrivateReadService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Local-site publication command for tasks already owned by content-automation. */
@Service
public class BlogAgentPublishService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final BlogPrivateReadService privateReadService;
    private final ContentAgentPostService contentPostService;
    private final BlogAgentTaskReadService taskReadService;

    public BlogAgentPublishService(JdbcTemplate jdbcTemplate,
                                   TransactionTemplate transactionTemplate,
                                   ObjectMapper objectMapper,
                                   BlogPrivateReadService privateReadService,
                                   ContentAgentPostService contentPostService,
                                   BlogAgentTaskReadService taskReadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.privateReadService = privateReadService;
        this.contentPostService = contentPostService;
        this.taskReadService = taskReadService;
    }

    public BlogPostVO publish(long userId, long taskId) {
        return transactionTemplate.execute(status -> {
            BlogAgentTask task = taskReadService.findOwnedTask(userId, taskId);
            if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "博客生成助手任务不存在");
            if (!"ready".equals(task.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "文章尚未生成完成");
            }
            if (task.getPublishedPostId() != null) {
                BlogPostVO existing = privateReadService.findMine(userId, task.getPublishedPostId());
                if (existing != null) return existing;
            }
            String title = defaultText(task.getTitle(), "未命名文章");
            String summary = defaultText(task.getSummary(), excerpt(task.getContent(), 160));
            String content = defaultText(task.getContent(), "");
            String category = defaultText(task.getCategoryId(), "career");
            List<String> tags = parseTags(task.getTagsJson());
            BlogPostVO published = contentPostService.createBlog(userId,
                    new ContentPostInput(title, summary, content, category, null, tags), true, null);
            if (published == null || published.getId() == null) {
                throw new IllegalStateException("文章发布后读取失败");
            }
            long postId = published.getId();
            jdbcTemplate.update("UPDATE blog_agent_task SET published_post_id = ?"
                            + " WHERE id = ? AND user_id = ? AND status = 'ready'",
                    postId, taskId, userId);
            return published;
        });
    }

    /** Reads the task's published post without changing task or post state. */
    public BlogPostVO findPublishedPost(long userId, long taskId) {
        BlogAgentTask task = taskReadService.findOwnedTask(userId, taskId);
        if (task == null || task.getPublishedPostId() == null) {
            return null;
        }
        return privateReadService.findMine(userId, task.getPublishedPostId());
    }

    private List<String> parseTags(String tagsJson) {
        try {
            return objectMapper.readValue(defaultText(tagsJson, "[]"),
                    new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("文章标签保存失败", exception);
        }
    }

    private static String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String excerpt(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }
}
