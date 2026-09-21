package com.xander.lab.blogagent.application;

import com.xander.lab.blogagent.domain.BlogAgentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** JDBC event store for content-owned BlogAgent SSE replay. */
@Service
public class BlogAgentEventStore {

    private final JdbcTemplate jdbcTemplate;

    public BlogAgentEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clear(long taskId) {
        jdbcTemplate.update("DELETE FROM blog_agent_event WHERE task_id = ?", taskId);
    }

    public long append(long taskId, String eventType, String data) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO blog_agent_event (task_id, event_type, data_content) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, taskId);
            statement.setString(2, eventType);
            statement.setString(3, data == null ? "" : data);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("博客生成助手事件持久化失败");
        return key.longValue();
    }

    public List<BlogAgentEvent> listAfter(long taskId, long afterEventId) {
        return jdbcTemplate.query(
                "SELECT id, task_id, event_type, data_content, created_at FROM blog_agent_event"
                        + " WHERE task_id = ? AND id > ? ORDER BY id ASC LIMIT 200",
                this::map, taskId, afterEventId);
    }

    private BlogAgentEvent map(ResultSet rs, int rowNum) throws SQLException {
        BlogAgentEvent event = new BlogAgentEvent();
        event.setId(rs.getObject("id", Long.class));
        event.setTaskId(rs.getObject("task_id", Long.class));
        event.setEventType(rs.getString("event_type"));
        event.setDataContent(rs.getString("data_content"));
        java.sql.Timestamp timestamp = rs.getTimestamp("created_at");
        event.setCreatedAt(timestamp == null ? null : timestamp.toLocalDateTime());
        return event;
    }
}
