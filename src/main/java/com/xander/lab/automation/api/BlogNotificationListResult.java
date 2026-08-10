package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Paginated result plus unread count for the notification bell. */
@Data
@Builder
public class BlogNotificationListResult {
    private List<BlogNotificationVO> records;
    private long total;
    private long unreadCount;
}