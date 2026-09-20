package com.xander.lab.automation.api;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Paginated notification records and the user's current unread count. */
@Data
@Builder
public class BlogNotificationListResult {

    private List<BlogNotificationVO> records;
    private long total;
    private long unreadCount;
}
