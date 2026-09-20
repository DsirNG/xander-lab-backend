package com.xander.lab.publishing.application;

import com.xander.lab.common.PageData;
import com.xander.lab.publishing.api.AgentImageVO;
import com.xander.lab.publishing.api.BlogMediaAssetVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Database-backed private media reads owned by content-automation. */
@Service
public class BlogMediaReadService {

    private static final String SELECT_COLUMNS = "SELECT id, user_id, url, original_name, stored_name,"
            + " size, content_type, width, height, source_type, agent_task_id, generation_meta, created_at"
            + " FROM blog_media_asset";

    private final JdbcTemplate jdbcTemplate;

    public BlogMediaReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BlogMediaAssetVO> images(long userId, String scope, String keyword) {
        int limit = "recent".equals(scope) ? 24 : 60;
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append(" WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if ("gif".equals(scope)) {
            sql.append(" AND content_type = ?");
            args.add("image/gif");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND original_name LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::asset, args.toArray());
    }

    public PageData<AgentImageVO> agentImages(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 60);
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_media_asset WHERE user_id = ? AND source_type = ?",
                Long.class, userId, "agent_generated");
        List<AgentImageVO> records = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE user_id = ? AND source_type = ?"
                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                this::asset,
                userId, "agent_generated", safeSize, (safePage - 1) * safeSize)
                .stream()
                .map(AgentImageVO::from)
                .toList();

        PageData<AgentImageVO> result = new PageData<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setCurrent(safePage);
        result.setSize(safeSize);
        result.setPages(total == 0 ? 0 : (total + safeSize - 1) / safeSize);
        result.setHasMore(safePage * (long) safeSize < total);
        return result;
    }

    private BlogMediaAssetVO asset(ResultSet rs, int rowNum) throws SQLException {
        return new BlogMediaAssetVO(
                rs.getLong("id"),
                rs.getLong("user_id"),
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
                rs.getTimestamp("created_at") == null
                        ? null : rs.getTimestamp("created_at").toLocalDateTime());
    }
}
