package com.xander.lab.blogagent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogPrivateReadService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

/** Local-site publication command for tasks already owned by content-automation. */
@Service
public class BlogAgentPublishService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final BlogPrivateReadService privateReadService;
    private final BlogAgentTaskReadService taskReadService;

    public BlogAgentPublishService(JdbcTemplate jdbcTemplate,
                                   TransactionTemplate transactionTemplate,
                                   ObjectMapper objectMapper,
                                   BlogPrivateReadService privateReadService,
                                   BlogAgentTaskReadService taskReadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.privateReadService = privateReadService;
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
            String author = jdbcTemplate.query(
                    "SELECT COALESCE(NULLIF(nickname, ''), NULLIF(username, ''), 'DinQorAI')"
                            + " FROM sys_user WHERE id = ?",
                    (rs, rowNum) -> rs.getString(1), userId).stream().findFirst().orElse("DinQorAI");
            KeyHolder keyHolder = new GeneratedKeyHolder();
            String finalAuthor = author;
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO blog_post (user_id, title, summary, content, category_id, author,"
                                + " read_time, status, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, userId);
                statement.setString(2, title);
                statement.setString(3, summary);
                statement.setString(4, content);
                statement.setString(5, category);
                statement.setString(6, finalAuthor);
                statement.setString(7, Math.max(1, content.length() / 500) + " min");
                statement.setObject(8, LocalDate.now());
                return statement;
            }, keyHolder);
            Number postKey = keyHolder.getKey();
            if (postKey == null) throw new IllegalStateException("文章发布失败");
            long postId = postKey.longValue();
            saveTags(postId, task.getTagsJson());
            jdbcTemplate.update("UPDATE blog_agent_task SET published_post_id = ?"
                            + " WHERE id = ? AND user_id = ? AND status = 'ready'",
                    postId, taskId, userId);
            BlogPostVO published = privateReadService.findMine(userId, postId);
            if (published == null) throw new IllegalStateException("文章发布后读取失败");
            return published;
        });
    }

    private void saveTags(long postId, String tagsJson) {
        try {
            List<String> tags = objectMapper.readValue(defaultText(tagsJson, "[]"),
                    new TypeReference<>() { });
            for (String tag : tags.stream().filter(StringUtils::hasText).map(String::trim).distinct().limit(8).toList()) {
                jdbcTemplate.update("INSERT INTO blog_tag (name) VALUES (?) ON DUPLICATE KEY UPDATE id = id", tag);
                Long tagId = jdbcTemplate.queryForObject("SELECT id FROM blog_tag WHERE name = ?", Long.class, tag);
                jdbcTemplate.update("INSERT INTO blog_post_tag (post_id, tag_id) VALUES (?, ?)"
                                + " ON DUPLICATE KEY UPDATE post_id = post_id", postId, tagId);
            }
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
