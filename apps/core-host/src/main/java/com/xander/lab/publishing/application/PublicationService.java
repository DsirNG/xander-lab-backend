package com.xander.lab.publishing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.content.api.BlogPublisher;
import com.xander.lab.content.api.PublicationPort;
import com.xander.lab.content.api.PublishCommand;
import com.xander.lab.content.api.PublishResult;
import com.xander.lab.publishing.infrastructure.BlogPublishRecord;
import com.xander.lab.publishing.infrastructure.BlogPublishRecordMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

/**
 * 发布编排：幂等判重、平台选路、落 {@code blog_publish_record}、CSDN 限流。
 *
 * <p><b>同时是 {@link PublicationPort} 的实现。</b>这是零换算的：两个端口方法的参数与返回值
 * 本来就已经全是 {@code content-api} 里的类型（{@link PublishCommand} / {@link PublishResult}），
 * 所以类体不需要改动，只需多一个 {@code implements} —— 与 {@code BillingPort}
 * “选与现有实现同形的端口形状”是同一个手法。</p>
 *
 * <p>同域的 {@code BlogPlanRunService} 与 {@code BlogAgentService} 仍然注入本具体类：
 * 它们与内容域同在 {@code dinqor-content-automation}，拆完还在一个进程里，不必绕端口。</p>
 */
@Service
public class PublicationService implements PublicationPort {
    /** 每用户每日 CSDN 同步上限（按成功发布记录计数） */
    static final int CSDN_DAILY_SYNC_LIMIT = 5;
    /** 连续同步防抖窗口：CSDN 对短时间重复发布会拒绝，提前拦截并以 429 提示而非 500 */
    private static final long CSDN_COOLDOWN_SECONDS = 30;
    private static final String CSDN_COOLDOWN_PREFIX = "csdn:sync:cooldown:";

    private final Map<String, BlogPublisher> publishers;
    private final BlogPublishRecordMapper recordMapper;
    private final StringRedisTemplate redisTemplate;

    public PublicationService(List<BlogPublisher> publishers, BlogPublishRecordMapper recordMapper,
                              StringRedisTemplate redisTemplate) {
        this.publishers = publishers.stream().collect(Collectors.toUnmodifiableMap(
                BlogPublisher::platform, Function.identity()));
        this.recordMapper = recordMapper;
        this.redisTemplate = redisTemplate;
    }

    public PublishResult publish(PublishCommand command, String platform) {
        BlogPublisher publisher = publishers.get(platform);
        if (publisher == null) throw new IllegalArgumentException("Unsupported publishing platform: " + platform);
        BlogPublishRecord existing = recordMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BlogPublishRecord>()
                .eq("task_id", command.taskId()).eq("platform", platform));
        if (existing != null && "SUCCEEDED".equals(existing.getStatus())
                && existing.getExternalId() != null && !existing.getExternalId().isBlank()) {
            return new PublishResult(platform, existing.getExternalId(), existing.getUrl());
        }
        // 限流放行检查放在幂等返回之后：重复同步已成功文章不消耗额度
        if ("csdn".equals(platform)) {
            enforceCsdnSyncPolicy(command.userId());
        }
        BlogPublishRecord record = existing == null ? new BlogPublishRecord() : existing;
        record.setTaskId(command.taskId()); record.setUserId(command.userId()); record.setPlatform(platform);
        record.setStatus("PENDING"); record.setAttempts((record.getAttempts() == null ? 0 : record.getAttempts()) + 1);
        if (record.getId() == null) recordMapper.insert(record); else recordMapper.updateById(record);
        try {
            PublishResult result = publisher.publish(command);
            record.setExternalId(result.externalId()); record.setUrl(result.url()); record.setStatus("SUCCEEDED");
            record.setErrorCode(null); record.setErrorMessage(null);
            record.setPublishedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            return result;
        } catch (RuntimeException ex) {
            record.setStatus("FAILED"); record.setErrorCode("PUBLISH_FAILED");
            record.setErrorMessage(ex.getMessage() == null ? "publication failed" : ex.getMessage().substring(0, Math.min(1000, ex.getMessage().length())));
            recordMapper.updateById(record);
            throw ex;
        }
    }

    /** Returns a proven durable publication result without contacting the target platform. */
    @Override
    @Transactional(readOnly = true)
    public PublishResult findSucceeded(Long userId, Long taskId, String platform) {
        if (userId == null || taskId == null || platform == null || platform.isBlank()) {
            return null;
        }
        BlogPublishRecord record = recordMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BlogPublishRecord>()
                .eq("task_id", taskId)
                .eq("user_id", userId)
                .eq("platform", platform)
                .eq("status", "SUCCEEDED")
                .last("LIMIT 1"));
        if (record == null || !"SUCCEEDED".equals(record.getStatus())
                || record.getExternalId() == null || record.getExternalId().isBlank()) {
            return null;
        }
        return new PublishResult(platform, record.getExternalId(), record.getUrl());
    }

    /** CSDN 同步限流：每用户每日最多 5 次成功同步；连续同步间隔至少 30 秒。 */
    private void enforceCsdnSyncPolicy(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "未登录或登录已过期");
        }
        boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                CSDN_COOLDOWN_PREFIX + userId, "1", CSDN_COOLDOWN_SECONDS, TimeUnit.SECONDS));
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "CSDN 同步过于频繁，请稍后再试");
        }
        // 与数据库 published_at 的单位一致（服务器本地时区、UTC 语义），按自然日计数
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Long syncedToday = recordMapper.selectCount(new LambdaQueryWrapper<BlogPublishRecord>()
                .eq(BlogPublishRecord::getUserId, userId)
                .eq(BlogPublishRecord::getPlatform, "csdn")
                .eq(BlogPublishRecord::getStatus, "SUCCEEDED")
                .ge(BlogPublishRecord::getPublishedAt, dayStart));
        if (syncedToday != null && syncedToday >= CSDN_DAILY_SYNC_LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "今日 CSDN 同步已达上限（" + CSDN_DAILY_SYNC_LIMIT + " 篇）");
        }
    }
}
