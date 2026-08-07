package com.xander.lab.publishing.application;

import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import com.xander.lab.publishing.infrastructure.BlogPublishRecord;
import com.xander.lab.publishing.infrastructure.BlogPublishRecordMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

@Service
public class PublicationService {
    private final Map<String, BlogPublisher> publishers;
    private final BlogPublishRecordMapper recordMapper;

    public PublicationService(List<BlogPublisher> publishers, BlogPublishRecordMapper recordMapper) {
        this.publishers = publishers.stream().collect(Collectors.toUnmodifiableMap(
                BlogPublisher::platform, Function.identity()));
        this.recordMapper = recordMapper;
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
        BlogPublishRecord record = existing == null ? new BlogPublishRecord() : existing;
        record.setTaskId(command.taskId()); record.setUserId(command.userId()); record.setPlatform(platform);
        record.setStatus("PENDING"); record.setAttempts((record.getAttempts() == null ? 0 : record.getAttempts()) + 1);
        record.setUpdatedAt(LocalDateTime.now());
        if (record.getCreatedAt() == null) record.setCreatedAt(record.getUpdatedAt());
        if (record.getId() == null) recordMapper.insert(record); else recordMapper.updateById(record);
        try {
            PublishResult result = publisher.publish(command);
            record.setExternalId(result.externalId()); record.setUrl(result.url()); record.setStatus("SUCCEEDED");
            record.setErrorCode(null); record.setErrorMessage(null);
            record.setPublishedAt(LocalDateTime.now()); record.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            return result;
        } catch (RuntimeException ex) {
            record.setStatus("FAILED"); record.setErrorCode("PUBLISH_FAILED");
            record.setErrorMessage(ex.getMessage() == null ? "publication failed" : ex.getMessage().substring(0, Math.min(1000, ex.getMessage().length())));
            record.setUpdatedAt(LocalDateTime.now()); recordMapper.updateById(record);
            throw ex;
        }
    }
}
