package com.xander.lab.automation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.automation.domain.BlogPlanRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BlogPlanRunMapper extends BaseMapper<BlogPlanRun> {

    @Select("""
            SELECT CASE
                WHEN EXISTS (
                    SELECT 1
                    FROM blog_plan_run
                    WHERE plan_id = #{planId}
                      AND scheduled_at = #{scheduledAt}
                ) THEN 1
                ELSE 0
            END
            """)
    boolean existsForPlanAndScheduledAt(@org.apache.ibatis.annotations.Param("planId") Long planId,
                                        @org.apache.ibatis.annotations.Param("scheduledAt") java.time.Instant scheduledAt);

    /** Terminal state for runs left in-flight by a crashed scheduler instance. */
    @Update("""
            UPDATE blog_plan_run
            SET status = 'FAILED',
                stage_error = 'stale',
                error_message = #{errorMessage},
                finished_at = CURRENT_TIMESTAMP(3)
            WHERE plan_id = #{planId}
              AND status IN ('GENERATING', 'REVIEWING', 'PUBLISHING')
            """)
    int failInFlightRunsForPlan(@Param("planId") Long planId,
                                @Param("errorMessage") String errorMessage);

    /** Local post IDs this plan already published successfully, newest first.
     * Feeds the "avoid repeating published articles" hint for the next run. */
    @Select("""
            SELECT local_post_id
            FROM blog_plan_run
            WHERE plan_id = #{planId}
              AND status = 'SUCCEEDED'
              AND local_post_id IS NOT NULL
            ORDER BY finished_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<Long> selectRecentLocalPostIds(@Param("planId") Long planId,
                                        @Param("limit") int limit);

    /** True when any run of the plan already reached SUCCEEDED. Used by stale
     * recovery to finish a one-shot plan as success when its run completed but
     * the final bookkeeping was interrupted. */
    @Select("""
            SELECT EXISTS(
                SELECT 1
                FROM blog_plan_run
                WHERE plan_id = #{planId}
                  AND status = 'SUCCEEDED'
            )
            """)
    boolean hasSucceededRun(@Param("planId") Long planId);
}