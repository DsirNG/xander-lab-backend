package com.xander.lab.automation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.automation.domain.BlogPlanRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}