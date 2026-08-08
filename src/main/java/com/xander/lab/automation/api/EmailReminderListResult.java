package com.xander.lab.automation.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
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

    public static EmailReminderListResult of(
            IPage<EmailReminderTaskVO> page,
            EmailReminderStatsVO stats) {
        long current = page.getCurrent();
        long pages = page.getPages();
        return EmailReminderListResult.builder()
                .records(page.getRecords())
                .total(page.getTotal())
                .current(current)
                .pages(pages)
                .size(page.getSize())
                .hasMore(current < pages)
                .stats(stats)
                .build();
    }
}
