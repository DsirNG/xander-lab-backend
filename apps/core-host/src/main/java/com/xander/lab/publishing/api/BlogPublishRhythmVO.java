package com.xander.lab.publishing.api;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class BlogPublishRhythmVO {
    private LocalDate weekStart;
    private BigDecimal dailyAverage;
    private List<DayCount> days;

    public record DayCount(LocalDate date, long count) {}
}
