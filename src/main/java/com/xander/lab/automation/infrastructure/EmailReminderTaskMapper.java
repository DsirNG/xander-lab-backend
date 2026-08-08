package com.xander.lab.automation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.automation.domain.EmailReminderTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface EmailReminderTaskMapper extends BaseMapper<EmailReminderTask> {

    @Select("SELECT id FROM sys_user WHERE id = #{userId} FOR UPDATE")
    Long lockUserForReminderCreation(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM email_reminder_task
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('PENDING', 'PAUSED', 'SENDING')
            """)
    long countActiveTasks(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM email_reminder_task
            WHERE user_id = #{userId}
              AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)
            """)
    long countCreatedInPastDay(@Param("userId") Long userId);

    @Select("""
            SELECT CASE
                WHEN #{scheduledAt} >= TIMESTAMPADD(
                    SECOND,
                    #{minimumLeadSeconds},
                    CURRENT_TIMESTAMP(3)
                ) THEN TRUE
                ELSE FALSE
            END
            """)
    boolean isScheduledAtLeastLeadTime(
            @Param("scheduledAt") Instant scheduledAt,
            @Param("minimumLeadSeconds") long minimumLeadSeconds
    );

    @Select("""
            SELECT CASE
                WHEN scheduled_at > CURRENT_TIMESTAMP(3) THEN TRUE
                ELSE FALSE
            END
            FROM email_reminder_task
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
            """)
    boolean isScheduledInFuture(@Param("id") Long id,
                                @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM email_reminder_task
            WHERE status = 'PENDING'
              AND deleted_at IS NULL
              AND scheduled_at <= CURRENT_TIMESTAMP(3)
              AND (
                  send_attempts = 0
                  OR updated_at <= TIMESTAMPADD(
                      MICROSECOND,
                      -(#{retryDelayMs} * 1000),
                      CURRENT_TIMESTAMP(3)
                  )
              )
            ORDER BY scheduled_at ASC, id ASC
            LIMIT #{batchSize}
            """)
    List<EmailReminderTask> selectDueTasks(
                                           @Param("retryDelayMs") long retryDelayMs,
                                           @Param("batchSize") int batchSize);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'SENDING',
                processing_token = #{processingToken},
                claimed_at = CURRENT_TIMESTAMP(3),
                send_attempts = send_attempts + 1,
                error_message = NULL
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'PENDING'
              AND scheduled_at <= CURRENT_TIMESTAMP(3)
            """)
    int claimForSending(@Param("id") Long id,
                        @Param("processingToken") String processingToken);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'SENT',
                sent_at = CURRENT_TIMESTAMP(3),
                processing_token = NULL,
                claimed_at = NULL,
                error_message = NULL
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'SENDING'
              AND processing_token = #{processingToken}
            """)
    int markSent(@Param("id") Long id,
                 @Param("processingToken") String processingToken);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'PENDING',
                scheduled_at = #{nextScheduledAt},
                sent_at = CURRENT_TIMESTAMP(3),
                send_attempts = 0,
                processing_token = NULL,
                claimed_at = NULL,
                error_message = NULL
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'SENDING'
              AND processing_token = #{processingToken}
            """)
    int markSentAndReschedule(@Param("id") Long id,
                              @Param("processingToken") String processingToken,
                              @Param("nextScheduledAt") Instant nextScheduledAt);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'PENDING',
                scheduled_at = #{nextScheduledAt},
                error_message = NULL,
                processing_token = NULL,
                claimed_at = NULL
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('PAUSED', 'FAILED')
            """)
    int resumeOwnedTaskAt(@Param("id") Long id,
                          @Param("userId") Long userId,
                          @Param("nextScheduledAt") Instant nextScheduledAt);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'FAILED',
                processing_token = NULL,
                claimed_at = NULL,
                error_message = #{errorMessage}
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'SENDING'
              AND processing_token = #{processingToken}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("processingToken") String processingToken,
                   @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'PENDING',
                processing_token = NULL,
                claimed_at = NULL,
                error_message = #{errorMessage}
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'SENDING'
              AND processing_token = #{processingToken}
            """)
    int requeueAfterFailure(@Param("id") Long id,
                            @Param("processingToken") String processingToken,
                            @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'FAILED',
                processing_token = NULL,
                claimed_at = NULL,
                error_message = #{errorMessage}
            WHERE status = 'SENDING'
              AND deleted_at IS NULL
              AND claimed_at < TIMESTAMPADD(
                  MICROSECOND,
                  -(#{claimTimeoutMs} * 1000),
                  CURRENT_TIMESTAMP(3)
              )
            """)
    int failStaleClaims(@Param("claimTimeoutMs") long claimTimeoutMs,
                        @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'PAUSED'
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status = 'PENDING'
            """)
    int pauseOwnedTask(@Param("id") Long id,
                       @Param("userId") Long userId);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'PENDING',
                error_message = NULL,
                processing_token = NULL,
                claimed_at = NULL
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('PAUSED', 'FAILED')
            """)
    int resumeOwnedTask(@Param("id") Long id,
                        @Param("userId") Long userId);

    @Update("""
            UPDATE email_reminder_task
            SET deleted_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status <> 'SENDING'
            """)
    int deleteOwnedTaskUnlessSending(@Param("id") Long id,
                                     @Param("userId") Long userId);
}
