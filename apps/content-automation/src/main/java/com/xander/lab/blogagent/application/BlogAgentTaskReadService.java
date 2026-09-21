package com.xander.lab.blogagent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.api.BlogAgentSessionVO;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.blogagent.domain.BlogAgentMessage;
import com.xander.lab.blogagent.domain.BlogAgentSource;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.domain.BlogAgentVersion;
import com.xander.lab.blogagent.infrastructure.BlogAgentReadProperties;
import com.xander.lab.content.api.MediaAssetRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * JDBC read owner for the BlogAgent task/session surface.
 *
 * <p>The service intentionally maps rows into content-automation-owned read models instead of
 * importing core-host MyBatis entities. Detail recovery preserves the legacy stale-running-task
 * behavior with an ownership-scoped conditional update.</p>
 */
@Slf4j
@Service
public class BlogAgentTaskReadService {

    private static final String TASK_COLUMNS = """
            SELECT id, user_id, client_request_id, input, input_type, audience, tone,
                   status, stage, title, summary, content, outline, content_boundary,
                   knowledge_graph_json, category_id, tags_json, review,
                   illustration_status, illustration_error, published_post_id,
                   error_message, created_at, updated_at
            FROM blog_agent_task
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BlogAgentReadProperties properties;

    public BlogAgentTaskReadService(JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper,
                                    BlogAgentReadProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Lists the current user's most recently updated task sessions. */
    @Transactional(readOnly = true)
    public List<BlogAgentSessionVO> listSessions(long userId) {
        return jdbcTemplate.query(
                TASK_COLUMNS + " WHERE user_id = ? ORDER BY updated_at DESC LIMIT 50",
                this::mapTask,
                userId).stream()
                .map(task -> BlogAgentSessionVO.builder()
                        .id(task.getId())
                        .title(defaultText(task.getTitle(), excerpt(task.getInput(), 36)))
                        .input(task.getInput())
                        .status(task.getStatus())
                        .stage(task.getStage())
                        .createdAt(task.getCreatedAt())
                        .updatedAt(task.getUpdatedAt())
                        .build())
                .toList();
    }

    /** Returns one fully hydrated task, enforcing ownership before any child read. */
    @Transactional
    public BlogAgentTaskVO detail(long userId, long taskId) {
        BlogAgentTask task = requireOwnedTask(userId, taskId);
        task = resolveStaleTask(task, userId);

        BlogAgentTaskVO result = new BlogAgentTaskVO();
        result.setTask(task);
        result.setTags(readTags(task.getTagsJson()));
        result.setContentBoundary(readObject(task.getContentBoundary()));
        result.setKnowledgeGraph(readObject(task.getKnowledgeGraphJson()));
        result.setSources(jdbcTemplate.query(
                "SELECT id, task_id, title, url, publisher, excerpt, reliability, retrieved_at"
                        + " FROM blog_agent_source WHERE task_id = ? ORDER BY id ASC",
                this::mapSource, taskId));
        result.setVersions(jdbcTemplate.query(
                "SELECT id, task_id, version_no, title, summary, content, change_note, created_at"
                        + " FROM blog_agent_version WHERE task_id = ? ORDER BY version_no DESC",
                this::mapVersion, taskId));
        result.setIllustrations(jdbcTemplate.query(
                "SELECT id, user_id, url, original_name, stored_name, size, content_type,"
                        + " width, height, source_type, agent_task_id, generation_meta, created_at"
                        + " FROM blog_media_asset WHERE user_id = ? AND agent_task_id = ?"
                        + " ORDER BY id ASC",
                this::mapMedia, userId, taskId));
        result.setMessages(jdbcTemplate.query(
                "SELECT id, task_id, role, kind, stage, content, created_at"
                        + " FROM blog_agent_message WHERE task_id = ? ORDER BY id ASC",
                this::mapMessage, taskId));
        return result;
    }

    BlogAgentTask findOwnedTask(long userId, long taskId) {
        List<BlogAgentTask> tasks = jdbcTemplate.query(
                TASK_COLUMNS + " WHERE id = ? AND user_id = ?",
                this::mapTask,
                taskId, userId);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    BlogAgentTask findByClientRequestId(long userId, String clientRequestId) {
        if (clientRequestId == null) return null;
        List<BlogAgentTask> tasks = jdbcTemplate.query(
                TASK_COLUMNS + " WHERE user_id = ? AND client_request_id = ? LIMIT 1",
                this::mapTask,
                userId, clientRequestId);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    /** Returns only a ready task for the cross-service idempotency lookup. */
    public BlogAgentTask findReadyByClientRequestId(long userId, String clientRequestId) {
        BlogAgentTask task = findByClientRequestId(userId, clientRequestId);
        return task != null && "ready".equals(task.getStatus()) ? task : null;
    }

    private BlogAgentTask requireOwnedTask(long userId, long taskId) {
        BlogAgentTask task = findOwnedTask(userId, taskId);
        if (task == null) {
            throw new ResponseStatusException(NOT_FOUND, "博客生成助手任务不存在");
        }
        return task;
    }

    private BlogAgentTask resolveStaleTask(BlogAgentTask task, long userId) {
        if (!"running".equals(task.getStatus()) || task.getUpdatedAt() == null) {
            return task;
        }
        int staleMinutes = Math.max(1, properties.getStaleTaskMinutes());
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(staleMinutes);
        if (task.getUpdatedAt().isAfter(staleBefore)) {
            return task;
        }
        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_agent_version WHERE task_id = ?",
                Integer.class, task.getId());
        String status = versions != null && versions > 0 ? "ready" : "failed";
        String message = versions != null && versions > 0
                ? "任务执行中断，已恢复到上一版已完成文章"
                : "任务执行中断，请重新创建任务";
        int updated = jdbcTemplate.update(
                "UPDATE blog_agent_task SET status = ?, error_message = ?"
                        + " WHERE id = ? AND user_id = ? AND status = 'running'"
                        + " AND updated_at <= ?",
                status, message, task.getId(), userId, staleBefore);
        return updated == 1 ? requireOwnedTask(userId, task.getId()) : task;
    }

    private BlogAgentTask mapTask(ResultSet rs, int rowNum) throws SQLException {
        BlogAgentTask task = new BlogAgentTask();
        task.setId(rs.getObject("id", Long.class));
        task.setUserId(rs.getObject("user_id", Long.class));
        task.setClientRequestId(rs.getString("client_request_id"));
        task.setInput(rs.getString("input"));
        task.setInputType(rs.getString("input_type"));
        task.setAudience(rs.getString("audience"));
        task.setTone(rs.getString("tone"));
        task.setStatus(rs.getString("status"));
        task.setStage(rs.getString("stage"));
        task.setTitle(rs.getString("title"));
        task.setSummary(rs.getString("summary"));
        task.setContent(rs.getString("content"));
        task.setOutline(rs.getString("outline"));
        task.setContentBoundary(rs.getString("content_boundary"));
        task.setKnowledgeGraphJson(rs.getString("knowledge_graph_json"));
        task.setCategoryId(rs.getString("category_id"));
        task.setTagsJson(rs.getString("tags_json"));
        task.setReview(rs.getString("review"));
        task.setIllustrationStatus(rs.getString("illustration_status"));
        task.setIllustrationError(rs.getString("illustration_error"));
        task.setPublishedPostId(rs.getObject("published_post_id", Long.class));
        task.setErrorMessage(rs.getString("error_message"));
        task.setCreatedAt(toDateTime(rs, "created_at"));
        task.setUpdatedAt(toDateTime(rs, "updated_at"));
        return task;
    }

    private BlogAgentSource mapSource(ResultSet rs, int rowNum) throws SQLException {
        BlogAgentSource source = new BlogAgentSource();
        source.setId(rs.getObject("id", Long.class));
        source.setTaskId(rs.getObject("task_id", Long.class));
        source.setTitle(rs.getString("title"));
        source.setUrl(rs.getString("url"));
        source.setPublisher(rs.getString("publisher"));
        source.setExcerpt(rs.getString("excerpt"));
        source.setReliability(rs.getString("reliability"));
        source.setRetrievedAt(toDateTime(rs, "retrieved_at"));
        return source;
    }

    private BlogAgentVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        BlogAgentVersion version = new BlogAgentVersion();
        version.setId(rs.getObject("id", Long.class));
        version.setTaskId(rs.getObject("task_id", Long.class));
        version.setVersionNo(rs.getObject("version_no", Integer.class));
        version.setTitle(rs.getString("title"));
        version.setSummary(rs.getString("summary"));
        version.setContent(rs.getString("content"));
        version.setChangeNote(rs.getString("change_note"));
        version.setCreatedAt(toDateTime(rs, "created_at"));
        return version;
    }

    private BlogAgentMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        BlogAgentMessage message = new BlogAgentMessage();
        message.setId(rs.getObject("id", Long.class));
        message.setTaskId(rs.getObject("task_id", Long.class));
        message.setRole(rs.getString("role"));
        message.setKind(rs.getString("kind"));
        message.setStage(rs.getString("stage"));
        message.setContent(rs.getString("content"));
        message.setCreatedAt(toDateTime(rs, "created_at"));
        return message;
    }

    private MediaAssetRef mapMedia(ResultSet rs, int rowNum) throws SQLException {
        return new MediaAssetRef(
                rs.getObject("id", Long.class),
                rs.getObject("user_id", Long.class),
                rs.getString("url"),
                rs.getString("original_name"),
                rs.getString("stored_name"),
                rs.getObject("size", Long.class),
                rs.getString("content_type"),
                rs.getObject("width", Integer.class),
                rs.getObject("height", Integer.class),
                rs.getString("source_type"),
                rs.getObject("agent_task_id", Long.class),
                rs.getString("generation_meta"),
                toDateTime(rs, "created_at"));
    }

    private LocalDateTime toDateTime(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private List<String> readTags(String json) {
        try {
            return objectMapper.readValue(defaultText(json, "[]"), new TypeReference<>() {});
        } catch (Exception exception) {
            log.debug("blog-agent task tags are not a JSON array: {}", exception.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> readObject(String json) {
        try {
            return objectMapper.readValue(defaultText(json, "{}"), new TypeReference<>() {});
        } catch (Exception exception) {
            log.debug("blog-agent task object payload is not valid JSON: {}", exception.getMessage());
            return Map.of();
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String excerpt(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }
}
