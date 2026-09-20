package com.xander.lab.publishing.application;

import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostStatusUpdateRequest;
import com.xander.lab.publishing.api.BlogPostVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** JDBC-backed publishing mutations owned by content-automation. */
@Service
public class BlogMutationService {

    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_PUBLISHED = 1;
    private static final int STATUS_TRASHED = -1;

    private final JdbcTemplate jdbcTemplate;
    private final BlogPrivateReadService privateReadService;

    public BlogMutationService(JdbcTemplate jdbcTemplate, BlogPrivateReadService privateReadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.privateReadService = privateReadService;
    }

    @Transactional
    public BlogPostVO update(long userId, long postId, BlogPostDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        requireOwned(userId, postId);
        if (request.getTitle() == null && request.getSummary() == null
                && request.getContent() == null && request.getCategoryId() == null
                && request.getCoverImage() == null && request.getTags() == null) {
            throw new IllegalArgumentException("至少提供一个需要更新的字段");
        }

        List<String> setters = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(setters, args, "title", request.getTitle());
        add(setters, args, "summary", request.getSummary());
        add(setters, args, "content", request.getContent());
        add(setters, args, "category_id", request.getCategoryId());
        add(setters, args, "cover_image", request.getCoverImage());
        if (request.getContent() != null && !request.getContent().isBlank()) {
            add(setters, args, "read_time", Math.max(1, request.getContent().length() / 500) + " min");
        }
        args.add(postId);
        args.add(userId);
        jdbcTemplate.update("UPDATE blog_post SET " + String.join(", ", setters)
                + " WHERE id = ? AND user_id = ?", args.toArray());

        if (request.getTags() != null) {
            replaceTags(postId, request.getTags());
        }
        return privateReadService.findMine(userId, postId);
    }

    @Transactional
    public BlogPostVO updateStatus(long userId, long postId, BlogPostStatusUpdateRequest request) {
        if (request == null || request.getStatus() == null) {
            throw new IllegalArgumentException("状态不能为空");
        }
        PostState existing = requireOwned(userId, postId);
        int nextStatus = normalizeStatus(request.getStatus());
        if (nextStatus == STATUS_PUBLISHED && existing.publishedAt() == null) {
            jdbcTemplate.update("UPDATE blog_post SET status = ?, published_at = ?"
                            + " WHERE id = ? AND user_id = ?",
                    nextStatus, LocalDate.now(), postId, userId);
        } else {
            jdbcTemplate.update("UPDATE blog_post SET status = ? WHERE id = ? AND user_id = ?",
                    nextStatus, postId, userId);
        }
        return privateReadService.findMine(userId, postId);
    }

    @Transactional
    public void moveToTrash(long userId, long postId) {
        requireOwned(userId, postId);
        jdbcTemplate.update("UPDATE blog_post SET status = ? WHERE id = ? AND user_id = ?",
                STATUS_TRASHED, postId, userId);
    }

    @Transactional
    public void permanentlyDelete(long userId, long postId) {
        PostState existing = requireOwned(userId, postId);
        if (existing.status() != STATUS_TRASHED) {
            throw new IllegalArgumentException("仅回收站中的文章可以彻底删除");
        }
        jdbcTemplate.update("DELETE FROM blog_post_view WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM blog_post_tag WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM blog_post WHERE id = ? AND user_id = ?", postId, userId);
    }

    private PostState requireOwned(long userId, long postId) {
        List<PostState> posts = jdbcTemplate.query(
                "SELECT user_id, status, published_at FROM blog_post WHERE id = ?",
                (rs, rowNum) -> new PostState(
                        rs.getObject("user_id", Long.class),
                        rs.getObject("status", Integer.class),
                        rs.getObject("published_at", LocalDate.class)),
                postId);
        if (posts.isEmpty()) {
            throw new IllegalArgumentException("文章不存在");
        }
        PostState existing = posts.get(0);
        if (existing.userId() == null || existing.userId() != userId) {
            throw new IllegalArgumentException("无权操作该文章");
        }
        return existing;
    }

    private void replaceTags(long postId, List<String> tags) {
        jdbcTemplate.update("DELETE FROM blog_post_tag WHERE post_id = ?", postId);
        for (String tag : tags) {
            jdbcTemplate.update("INSERT INTO blog_tag (name) VALUES (?)"
                    + " ON DUPLICATE KEY UPDATE id = id", tag);
            Long tagId = jdbcTemplate.queryForObject(
                    "SELECT id FROM blog_tag WHERE name = ?", Long.class, tag);
            jdbcTemplate.update("INSERT INTO blog_post_tag (post_id, tag_id) VALUES (?, ?)"
                    + " ON DUPLICATE KEY UPDATE post_id = post_id", postId, tagId);
        }
    }

    private static void add(List<String> setters, List<Object> args, String column, Object value) {
        if (value != null) {
            setters.add(column + " = ?");
            args.add(value);
        }
    }

    private static int normalizeStatus(Integer status) {
        if (status == STATUS_DRAFT || status == STATUS_PUBLISHED || status == STATUS_TRASHED) {
            return status;
        }
        throw new IllegalArgumentException("不支持的文章状态");
    }

    private record PostState(Long userId, Integer status, LocalDate publishedAt) {
    }
}
