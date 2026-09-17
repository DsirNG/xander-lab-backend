package com.xander.lab.automation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.automation.api.BlogNotificationListResult;
import com.xander.lab.automation.api.BlogNotificationVO;
import com.xander.lab.automation.domain.BlogNotification;
import com.xander.lab.automation.infrastructure.BlogNotificationMapper;
import com.xander.lab.automation.spi.NotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reads and acknowledges user notifications for the Navbar bell. Notifications
 * are written by background plan runs (see BlogPlanRunService).
 *
 * <p>实现 {@link NotificationPort}（Wave 0B 第十刀）：这是 {@code knowledgebase} 发
 * 「大部头未自动出结构图」通知的唯一入口。端口方法 {@link #notifyResource} 与本类原有的
 * 方法<b>逐字同形</b>，所以这一刀在提供方只是加了一个 {@code implements} 与一个
 * {@code @Override} —— 可证明是纯重构。读侧四个方法（{@code list} / {@code unreadCount} /
 * {@code markRead} / {@code markAllRead}）服务的是前端 Navbar 铃铛，不跨域，<b>刻意不进端口</b>。</p> */
@Service
@RequiredArgsConstructor
public class BlogNotificationService implements NotificationPort {

    private final BlogNotificationMapper notificationMapper;

    @Transactional(readOnly = true)
    public BlogNotificationListResult list(Long userId, long page, long size) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        long safeSize = Math.min(Math.max(size, 1), 20);
        Page<BlogNotification> result = notificationMapper.selectPage(
                new Page<>(Math.max(page, 1), safeSize),
                new LambdaQueryWrapper<BlogNotification>()
                        .eq(BlogNotification::getUserId, userId)
                        .orderByDesc(BlogNotification::getCreatedAt)
                        .orderByDesc(BlogNotification::getId));
        return BlogNotificationListResult.builder()
                .records(result.getRecords().stream().map(this::toVO).toList())
                .total(result.getTotal())
                .unreadCount(notificationMapper.countUnread(userId))
                .build();
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        return notificationMapper.countUnread(userId);
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        int updated = notificationMapper.update(null, new LambdaUpdateWrapper<BlogNotification>()
                .eq(BlogNotification::getId, notificationId)
                .eq(BlogNotification::getUserId, userId)
                .set(BlogNotification::getIsRead, true));
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<BlogNotification>()
                .eq(BlogNotification::getUserId, userId)
                .eq(BlogNotification::getIsRead, false)
                .set(BlogNotification::getIsRead, true));
    }

    /**
     * 为「非计划类」业务写一条带回跳资源的通知。跨模块的唯一入口：
     * 其他模块要发通知只能调这里，不允许直接用 BlogNotificationMapper。
     *
     * <p>独立事务：通知只是旁路提醒，写不进去不该把调用方的主流程一起回滚。</p>
     *
     * @param resourceType 见 {@link NotificationPort#RESOURCE_KNOWLEDGE_FILE}
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long notifyResource(Long userId, String type, String title, String message,
                              String resourceType, Long resourceId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        BlogNotification notification = new BlogNotification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setResourceType(resourceType);
        notification.setResourceId(resourceId);
        notification.setIsRead(false);
        notificationMapper.insert(notification);
        return notification.getId();
    }

    private BlogNotificationVO toVO(BlogNotification notification) {
        return BlogNotificationVO.builder()
                .id(notification.getId())
                .type(notification.getType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .planId(notification.getPlanId())
                .runId(notification.getRunId())
                .resourceType(notification.getResourceType())
                .resourceId(notification.getResourceId())
                .isRead(notification.getIsRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}