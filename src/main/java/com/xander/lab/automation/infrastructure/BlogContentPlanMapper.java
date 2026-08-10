package com.xander.lab.automation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.automation.domain.BlogContentPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface BlogContentPlanMapper extends BaseMapper<BlogContentPlan> {

    @Select("SELECT id FROM sys_user WHERE id = #{userId} FOR UPDATE")
    Long lockUserForPlanCreation(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM blog_content_plan
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('ACTIVE', 'RUNNING', 'PAUSED')
            """)
    long countActivePlans(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM blog_content_plan
            WHERE user_id = #{userId}
              AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)
            """)
    long countCreatedInPastDay(@Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM blog_content_plan
            WHERE status = 'ACTIVE'
              AND deleted_at IS NULL
              AND next_run_at <= CURRENT_TIMESTAMP(3)
            ORDER BY next_run_at ASC, id ASC
            LIMIT #{batchSize}
            """)
    List<BlogContentPlan> selectDuePlans(@Param("batchSize") int batchSize);

    /** Atomically claims one due plan so a second scheduler instance skips it. */
    @Update("""
            UPDATE blog_content_plan
            SET status = 'RUNNING',
                processing_token = #{processingToken},
                claimed_at = CURRENT_TIMESTAMP(3),
                error_message = NULL
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'ACTIVE'
              AND next_run_at <= CURRENT_TIMESTAMP(3)
            """)
    int claimPlan(@Param("id") Long id,
                  @Param("processingToken") String processingToken);

    /** Plans claimed by a now-crashed instance: RUNNING but claimed too long ago. */
    @Select("""
            SELECT *
            FROM blog_content_plan
            WHERE status = 'RUNNING'
              AND deleted_at IS NULL
              AND claimed_at < TIMESTAMPADD(
                  MICROSECOND,
                  -(#{claimTimeoutMs} * 1000),
                  CURRENT_TIMESTAMP(3)
              )
            ORDER BY claimed_at ASC
            LIMIT #{batchSize}
            """)
    List<BlogContentPlan> selectStaleClaims(@Param("claimTimeoutMs") long claimTimeoutMs,
                                            @Param("batchSize") int batchSize);

    /** Marks a stale claim back to ACTIVE with a recomputed next run. */
    @Update("""
            UPDATE blog_content_plan
            SET status = 'ACTIVE',
                next_run_at = #{nextRunAt},
                processing_token = NULL,
                claimed_at = NULL,
                error_message = #{errorMessage}
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'RUNNING'
            """)
    int recoverStaleClaim(@Param("id") Long id,
                          @Param("nextRunAt") Instant nextRunAt,
                          @Param("errorMessage") String errorMessage);

    /** Reschedules a finished run to its next daily trigger. */
    @Update("""
            UPDATE blog_content_plan
            SET status = 'ACTIVE',
                next_run_at = #{nextRunAt},
                last_run_at = #{lastRunAt},
                processing_token = NULL,
                claimed_at = NULL,
                error_message = NULL
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'RUNNING'
              AND processing_token = #{processingToken}
            """)
    int markRunFinished(@Param("id") Long id,
                        @Param("processingToken") String processingToken,
                        @Param("nextRunAt") Instant nextRunAt,
                        @Param("lastRunAt") Instant lastRunAt);

    @Update("""
            UPDATE blog_content_plan
            SET status = 'ACTIVE',
                next_run_at = #{nextRunAt},
                error_message = NULL,
                processing_token = NULL,
                claimed_at = NULL
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('PAUSED', 'FAILED')
            """)
    int resumeOwnedPlanAt(@Param("id") Long id,
                          @Param("userId") Long userId,
                          @Param("nextRunAt") Instant nextRunAt);

    @Update("""
            UPDATE blog_content_plan
            SET status = 'PAUSED'
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status = 'ACTIVE'
            """)
    int pauseOwnedPlan(@Param("id") Long id,
                       @Param("userId") Long userId);

    @Update("""
            UPDATE blog_content_plan
            SET status = 'ACTIVE',
                error_message = NULL,
                processing_token = NULL,
                claimed_at = NULL
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('PAUSED', 'FAILED')
            """)
    int resumeOwnedPlan(@Param("id") Long id,
                        @Param("userId") Long userId);

    @Update("""
            UPDATE blog_content_plan
            SET status = 'CANCELLED',
                error_message = NULL,
                processing_token = NULL,
                claimed_at = NULL
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('ACTIVE', 'PAUSED', 'FAILED')
            """)
    int cancelOwnedPlan(@Param("id") Long id,
                        @Param("userId") Long userId);

    @Update("""
            UPDATE blog_content_plan
            SET deleted_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status <> 'RUNNING'
            """)
    int deleteOwnedPlanUnlessRunning(@Param("id") Long id,
                                     @Param("userId") Long userId);
}