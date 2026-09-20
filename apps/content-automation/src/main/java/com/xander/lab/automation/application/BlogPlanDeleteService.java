package com.xander.lab.automation.application;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Soft-delete use case for content plans. */
@Service
@RequiredArgsConstructor
public class BlogPlanDeleteService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void delete(Long userId, Long planId) {
        requireUser(userId);
        String status = jdbcTemplate.query(
                        "SELECT status FROM blog_content_plan "
                                + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                        (rs, rowNum) -> rs.getString("status"),
                        planId,
                        userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "定时发文计划不存在"));
        if ("RUNNING".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划正在执行，暂时不能删除");
        }
        int updated = jdbcTemplate.update(
                "UPDATE blog_content_plan SET deleted_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status <> 'RUNNING'",
                planId,
                userId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划状态已变化，请刷新后重试");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }
}
