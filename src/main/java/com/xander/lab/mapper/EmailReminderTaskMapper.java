package com.xander.lab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.entity.EmailReminderTask;
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
            SELECT *
            FROM email_reminder_task
            WHERE status = 'PENDING'
              AND deleted_at IS NULL
              AND scheduled_at <= #{now}
              AND (send_attempts = 0 OR updated_at <= #{retryBefore})
            ORDER BY scheduled_at ASC, id ASC
            LIMIT #{batchSize}
            """)
    List<EmailReminderTask> selectDueTasks(@Param("now") Instant now,
                                           @Param("retryBefore") Instant retryBefore,
                                           @Param("batchSize") int batchSize);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'SENDING',
                processing_token = #{processingToken},
                claimed_at = #{claimedAt},
                send_attempts = send_attempts + 1,
                error_message = NULL,
                updated_at = #{claimedAt}
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'PENDING'
              AND scheduled_at <= #{claimedAt}
            """)
    int claimForSending(@Param("id") Long id,
                        @Param("processingToken") String processingToken,
                        @Param("claimedAt") Instant claimedAt);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'SENT',
                sent_at = #{sentAt},
                processing_token = NULL,
                claimed_at = NULL,
                error_message = NULL,
                updated_at = #{sentAt}
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'SENDING'
              AND processing_token = #{processingToken}
            """)
    int markSent(@Param("id") Long id,
                 @Param("processingToken") String processingToken,
                 @Param("sentAt") Instant sentAt);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'FAILED',
                processing_token = NULL,
                claimed_at = NULL,
                error_message = #{errorMessage},
                updated_at = #{failedAt}
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'SENDING'
              AND processing_token = #{processingToken}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("processingToken") String processingToken,
                   @Param("errorMessage") String errorMessage,
                   @Param("failedAt") Instant failedAt);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'PENDING',
                processing_token = NULL,
                claimed_at = NULL,
                error_message = #{errorMessage},
                updated_at = #{retryAt}
            WHERE id = #{id}
              AND deleted_at IS NULL
              AND status = 'SENDING'
              AND processing_token = #{processingToken}
            """)
    int requeueAfterFailure(@Param("id") Long id,
                            @Param("processingToken") String processingToken,
                            @Param("errorMessage") String errorMessage,
                            @Param("retryAt") Instant retryAt);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'FAILED',
                processing_token = NULL,
                claimed_at = NULL,
                error_message = #{errorMessage},
                updated_at = #{recoveredAt}
            WHERE status = 'SENDING'
              AND deleted_at IS NULL
              AND claimed_at < #{staleBefore}
            """)
    int failStaleClaims(@Param("staleBefore") Instant staleBefore,
                        @Param("recoveredAt") Instant recoveredAt,
                        @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'PAUSED',
                updated_at = #{updatedAt}
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status = 'PENDING'
            """)
    int pauseOwnedTask(@Param("id") Long id,
                       @Param("userId") Long userId,
                       @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE email_reminder_task
            SET status = 'PENDING',
                error_message = NULL,
                processing_token = NULL,
                claimed_at = NULL,
                updated_at = #{updatedAt}
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status IN ('PAUSED', 'FAILED')
            """)
    int resumeOwnedTask(@Param("id") Long id,
                        @Param("userId") Long userId,
                        @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE email_reminder_task
            SET deleted_at = #{deletedAt},
                updated_at = #{deletedAt}
            WHERE id = #{id}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND status <> 'SENDING'
            """)
    int deleteOwnedTaskUnlessSending(@Param("id") Long id,
                                     @Param("userId") Long userId,
                                     @Param("deletedAt") Instant deletedAt);
}
