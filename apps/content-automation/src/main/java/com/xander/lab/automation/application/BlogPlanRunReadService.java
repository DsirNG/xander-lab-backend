package com.xander.lab.automation.application;

import com.xander.lab.automation.api.BlogPlanRunVO;
import com.xander.lab.automation.api.BlogPlanRunsPage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** JDBC-backed read use cases for content-plan execution history. */
@Service
@RequiredArgsConstructor
public class BlogPlanRunReadService {

    private static final String RUN_COLUMNS = """
            SELECT id, plan_id, scheduled_at, trigger_type, status, stage_error,
                   error_message, agent_task_id, local_post_id, review_pass,
                   review_reason, csdn_status, csdn_error_code, csdn_error_message,
                   csdn_external_id, csdn_url, juejin_status, juejin_error_code,
                   juejin_error_message, juejin_external_id, juejin_url,
                   started_at, finished_at, created_at
            FROM blog_plan_run
            """;

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public BlogPlanRunsPage list(Long userId, Long planId, long page, long size) {
        requireUser(userId);
        requireOwnedPlan(userId, planId);
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 50);
        long offset = (safePage - 1) * safeSize;
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_plan_run WHERE plan_id = ?",
                Long.class,
                planId);
        List<BlogPlanRunVO> records = jdbcTemplate.query(
                RUN_COLUMNS + "WHERE plan_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRun,
                planId,
                safeSize,
                offset);
        return BlogPlanRunsPage.builder()
                .records(records)
                .total(total == null ? 0 : total)
                .page(safePage)
                .size(safeSize)
                .build();
    }

    @Transactional(readOnly = true)
    public BlogPlanRunVO detail(Long userId, Long planId, Long runId) {
        requireUser(userId);
        requireOwnedPlan(userId, planId);
        List<BlogPlanRunVO> records = jdbcTemplate.query(
                RUN_COLUMNS + "WHERE plan_id = ? AND id = ?",
                this::mapRun,
                planId,
                runId);
        if (records.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "计划执行记录不存在");
        }
        return records.get(0);
    }

    private void requireOwnedPlan(Long userId, Long planId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_content_plan "
                        + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                Integer.class,
                planId,
                userId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定时发文计划不存在");
        }
    }

    private BlogPlanRunVO mapRun(ResultSet rs, int rowNum) throws SQLException {
        return BlogPlanRunVO.builder()
                .id(toLong(rs, "id"))
                .planId(toLong(rs, "plan_id"))
                .scheduledAt(toInstant(rs, "scheduled_at"))
                .triggerType(rs.getString("trigger_type"))
                .status(rs.getString("status"))
                .stageError(rs.getString("stage_error"))
                .errorMessage(rs.getString("error_message"))
                .agentTaskId(toLong(rs, "agent_task_id"))
                .localPostId(toLong(rs, "local_post_id"))
                .reviewPass(toBoolean(rs, "review_pass"))
                .reviewReason(rs.getString("review_reason"))
                .csdnStatus(rs.getString("csdn_status"))
                .csdnErrorCode(rs.getString("csdn_error_code"))
                .csdnErrorMessage(rs.getString("csdn_error_message"))
                .csdnExternalId(rs.getString("csdn_external_id"))
                .csdnUrl(rs.getString("csdn_url"))
                .juejinStatus(rs.getString("juejin_status"))
                .juejinErrorCode(rs.getString("juejin_error_code"))
                .juejinErrorMessage(rs.getString("juejin_error_message"))
                .juejinExternalId(rs.getString("juejin_external_id"))
                .juejinUrl(rs.getString("juejin_url"))
                .startedAt(toInstant(rs, "started_at"))
                .finishedAt(toInstant(rs, "finished_at"))
                .createdAt(toInstant(rs, "created_at"))
                .build();
    }

    private Long toLong(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.longValue();
    }

    private Boolean toBoolean(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return ((Number) value).intValue() != 0;
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }
}
