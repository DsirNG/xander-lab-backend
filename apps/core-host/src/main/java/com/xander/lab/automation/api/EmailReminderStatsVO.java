package com.xander.lab.automation.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailReminderStatsVO {

    /** 未删除任务总数 */
    private long total;

    /** 进行中：PENDING + SENDING */
    private long active;

    /** 已发送 */
    private long sent;

    /** 待发送：PENDING */
    private long pending;
}
