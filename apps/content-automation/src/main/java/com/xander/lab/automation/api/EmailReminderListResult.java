package com.xander.lab.automation.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailReminderListResult {

    private List<EmailReminderTaskVO> records;
    private long total;
    private long current;
    private long pages;
    private long size;
    private boolean hasMore;
    private EmailReminderStatsVO stats;
}
