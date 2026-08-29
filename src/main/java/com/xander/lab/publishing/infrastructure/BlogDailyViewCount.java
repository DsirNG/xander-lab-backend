package com.xander.lab.publishing.infrastructure;

import lombok.Data;

import java.time.LocalDate;

@Data
public class BlogDailyViewCount {
    private LocalDate date;
    private long views;
}
