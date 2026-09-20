package com.xander.lab.publishing.api;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Private publishing management statistics owned by content-automation. */
@Data
@Builder
public class BlogManageStatsVO {
    private long totalArticles;
    private long drafts;
    private long published;
    private long trashed;
    private long recentViews;
    private long recentPublished;
    private BigDecimal averageViews;
    private List<DailyViews> dailyViews;

    public record DailyViews(LocalDate date, long views) {
    }
}
