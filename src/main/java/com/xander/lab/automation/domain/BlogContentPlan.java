package com.xander.lab.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Daily content plan: a recurring topic (or multi-day topic queue) with one
 * or more user-provided local trigger times, and whether each run should
 * also sync to CSDN. Scheduling instants are stored as absolute UTC instants. */
@Data
@TableName("blog_content_plan")
public class BlogContentPlan {

    public static final String SCHEDULE_DAILY = "DAILY";
    public static final String SCHEDULE_WEEKLY = "WEEKLY";
    public static final String SCHEDULE_ONCE = "ONCE";

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** Terminal success for one-shot plans: the single scheduled trigger ran
     * and its run ended SUCCEEDED. Failed/rejected runs end in STATUS_FAILED. */
    public static final String STATUS_FINISHED = "FINISHED";
    /** Terminal failure for one-shot plans: the single run failed, was rejected
     * by compliance, or its executor crashed before completion. */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String topic;
    private String audience;
    private String tone;
    /** DAILY / WEEKLY / ONCE. Historical rows default to DAILY. */
    private String scheduleType;
    /** User-selected local date for ONCE or the first WEEKLY occurrence. */
    private LocalDate scheduledDate;
    /** DEEP / PRACTICAL / NEWS / OPINION, used to steer article generation. */
    private String aiOption;
    /** Optional personal knowledge material used as generation grounding. */
    private Long knowledgeMaterialId;
    /** True publishes immediately; false stores the generated local article as draft. */
    private Boolean autoPublish;
    /** IANA timezone, e.g. Asia/Shanghai */
    private String timezone;
    /** HH:mm local trigger time in `timezone` (single-time mode) */
    private String triggerTime;
    /** JSON array of HH:mm local trigger times in `timezone` (multi-time mode) */
    private String triggerTimesJson;
    /** JSON array of daily topic titles (multi-day topic mode) */
    private String topicsJson;
    /** Index of the next topic from `topicsJson` to use (0-based) */
    private Integer nextTopicIndex;
    private Boolean syncCsdn;
    private Boolean syncJuejin;
    /** True for one-shot plans (AI 生成计划): the plan fires exactly once at
     * `nextRunAt` and auto-finishes instead of rescheduling daily. */
    private Boolean runOnce;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private String status;
    private String errorMessage;
    private String processingToken;
    private Instant claimedAt;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static String toJson(List<String> values) {
        try {
            return JSON.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法序列化计划配置", e);
        }
    }

    public static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json,
                    JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("计划配置数据损坏", e);
        }
    }

    /** All daily trigger times: multi-time JSON when present, else legacy single time. */
    public List<String> allTriggerTimes() {
        List<String> times = fromJson(triggerTimesJson);
        if (times.isEmpty() && triggerTime != null && !triggerTime.isBlank()) {
            times.add(triggerTime);
        }
        return times;
    }

    /** The daily topic queue, or an empty list when single-topic mode. */
    public List<String> topics() {
        return fromJson(topicsJson);
    }

    /** Topic for the next run: queue entry at `nextTopicIndex` (cycled), else topic. */
    public String topicForNextRun() {
        List<String> queue = topics();
        if (queue.isEmpty()) {
            return topic;
        }
        int index = nextTopicIndex == null ? 0 : nextTopicIndex;
        return queue.get(index % queue.size());
    }
}
