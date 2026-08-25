package com.xander.lab.agent.domain;

import java.util.Locale;

/**
 * 智能体自己维护的一个任务步骤。
 *
 * <p>计划是会话级状态而不是日志：每一步循环都会把当前计划重新注入模型上下文，
 * 所以模型能看到自己之前的安排并据此调整，而不是每次从零重新决定。</p>
 *
 * @param title  这一步要做的事，面向用户可读
 * @param status 步骤状态，取值见 {@link Status}
 * @param note   可选备注：为什么这么排、卡在哪里、产出放在哪里
 */
public record AgentPlanItem(String title, String status, String note) {

    /** 任务步骤状态。 */
    public enum Status {
        /** 尚未开始。 */
        PENDING,
        /** 正在进行；同一时刻最多一个步骤处于该状态。 */
        IN_PROGRESS,
        /** 已完成。 */
        DONE,
        /** 主动放弃：需求变化或已被证明不必要，note 必须说明原因。 */
        DROPPED;

        /** 宽松解析模型输出的状态值，无法识别时按未开始处理。 */
        public static Status fromNullable(String value) {
            if (value == null || value.isBlank()) return PENDING;
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Status status : values()) {
                if (status.name().equals(normalized)) return status;
            }
            return PENDING;
        }

        /** 是否属于已经收口的状态，用于判断计划是否还有未完成项。 */
        public boolean settled() {
            return this == DONE || this == DROPPED;
        }
    }

    public AgentPlanItem {
        title = title == null ? "" : title.trim();
        status = Status.fromNullable(status).name();
        note = note == null ? "" : note.trim();
    }

    public Status statusValue() {
        return Status.fromNullable(status);
    }

    public boolean settled() {
        return statusValue().settled();
    }
}
