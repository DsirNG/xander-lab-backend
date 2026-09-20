package com.xander.lab.automation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** Body for creating a scheduled content plan. */
@Data
public class BlogContentPlanCreateRequest {

    @Size(max = 64, message = "幂等请求标识不能超过 64 个字符")
    @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "幂等请求标识格式不正确")
    private String clientRequestId;

    @Pattern(regexp = "DAILY|WEEKLY|ONCE", message = "计划类型只支持 DAILY / WEEKLY / ONCE")
    private String scheduleType;

    private LocalDate scheduledDate;

    @NotBlank(message = "请输入文章主题")
    @Size(max = 500, message = "文章主题不能超过 500 个字符")
    private String topic;

    @Size(max = 64, message = "时区不能超过 64 个字符")
    private String timezone;

    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "触发时间格式应为 HH:mm")
    private String triggerTime;

    @Size(max = 5, message = "每天最多设置 5 个触发时间")
    private List<@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "触发时间格式应为 HH:mm") String> triggerTimes;

    @Size(max = 30, message = "主题队列最多 30 个主题")
    private List<@Size(max = 500, message = "主题不能超过 500 个字符") String> topics;

    private Boolean syncCsdn;
    private Boolean syncJuejin;

    @Size(max = 120, message = "目标读者不能超过 120 个字符")
    private String audience;

    @Size(max = 60, message = "文风语气不能超过 60 个字符")
    private String tone;

    @Pattern(regexp = "DEEP|PRACTICAL|NEWS|OPINION", message = "不支持的 AI 规划方向")
    private String aiOption;

    private Long knowledgeMaterialId;
    private Boolean autoPublish;
}
