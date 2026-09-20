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

    private long total;
    private long active;
    private long sent;
    private long pending;
}
