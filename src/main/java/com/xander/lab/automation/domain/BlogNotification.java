package com.xander.lab.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** User-facing notification produced by background plan runs. Serves as the
 * durable fallback for users who are offline at trigger time; the Navbar bell
 * polls or subscribes to this table. */
@Data
@TableName("blog_notification")
public class BlogNotification {

    public static final String TYPE_REVIEW_REJECTED = "REVIEW_REJECTED";
    public static final String TYPE_CSDN_FAILED = "CSDN_FAILED";
    public static final String TYPE_JUEJIN_FAILED = "JUEJIN_FAILED";
    public static final String TYPE_RUN_FAILED = "RUN_FAILED";
    public static final String TYPE_PLAN_STALLED = "PLAN_STALLED";
    /** 大部头知识库文件未自动生成结构图，提示用户按章生成。 */
    public static final String TYPE_KNOWLEDGE_STRUCTURE_PENDING = "KNOWLEDGE_STRUCTURE_PENDING";

    /** 通用关联资源类型：知识库文件。 */
    public static final String RESOURCE_KNOWLEDGE_FILE = "KNOWLEDGE_FILE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String message;
    private Long planId;
    private Long runId;
    /** 通用关联资源类型，与 planId/runId 互斥使用；见 {@link #RESOURCE_KNOWLEDGE_FILE}。 */
    private String resourceType;
    /** 通用关联资源主键，配合 {@code resourceType} 解释。 */
    private Long resourceId;
    private Boolean isRead;
    private Instant createdAt;
    private Instant updatedAt;
}