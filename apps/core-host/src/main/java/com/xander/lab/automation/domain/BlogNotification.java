package com.xander.lab.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** User-facing notification produced by background plan runs. Serves as the
 * durable fallback for users who are offline at trigger time; the Navbar bell
 * polls or subscribes to this table.
 *
 * <p>⚠️ 这里的 {@code TYPE_*} 是 <b>automation 自己的词表</b>，由 {@code BlogPlanRunService} 使用。
 * 跨域消费方（{@code knowledgebase}）用的那两个常量（{@code KNOWLEDGE_STRUCTURE_PENDING} /
 * {@code KNOWLEDGE_FILE}）已经搬到 {@link com.xander.lab.automation.spi.NotificationPort}
 * 它们留在实体上的话，消费方为了拿常量仍要 import 这个实体，
 * 端口就白抽了。要加新的跨域通知类型，请加到端口上，不要加回这里。</p> */
@Data
@TableName("blog_notification")
public class BlogNotification {

    public static final String TYPE_REVIEW_REJECTED = "REVIEW_REJECTED";
    public static final String TYPE_CSDN_FAILED = "CSDN_FAILED";
    public static final String TYPE_JUEJIN_FAILED = "JUEJIN_FAILED";
    public static final String TYPE_RUN_FAILED = "RUN_FAILED";
    public static final String TYPE_PLAN_STALLED = "PLAN_STALLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String message;
    private Long planId;
    private Long runId;
    /** 通用关联资源类型，与 planId/runId 互斥使用；取值见
     * {@link com.xander.lab.automation.spi.NotificationPort#RESOURCE_KNOWLEDGE_FILE}。 */
    private String resourceType;
    /** 通用关联资源主键，配合 {@code resourceType} 解释。 */
    private Long resourceId;
    private Boolean isRead;
    private Instant createdAt;
    private Instant updatedAt;
}