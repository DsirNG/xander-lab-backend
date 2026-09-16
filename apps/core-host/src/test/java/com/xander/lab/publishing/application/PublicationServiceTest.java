package com.xander.lab.publishing.application;

import com.xander.lab.content.api.BlogPublisher;
import com.xander.lab.content.api.PublishCommand;
import com.xander.lab.content.api.PublishResult;
import com.xander.lab.publishing.infrastructure.BlogPublishRecord;
import com.xander.lab.publishing.infrastructure.BlogPublishRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

class PublicationServiceTest {
    private final BlogPublishRecordMapper records = mock(BlogPublishRecordMapper.class);
    private final BlogPublisher local = mock(BlogPublisher.class);
    private final BlogPublisher csdn = mock(BlogPublisher.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private PublicationService service;
    private final PublishCommand command = new PublishCommand(
            7L, 42L, "标题", "摘要", "正文", "backend", null, List.of("Java"));

    @BeforeEach
    void setUp() {
        when(local.platform()).thenReturn("local");
        when(local.publish(any())).thenReturn(new PublishResult("local", "post-1", "/blog/1"));
        when(csdn.platform()).thenReturn("csdn");
        when(csdn.publish(any())).thenReturn(new PublishResult("csdn", "csdn-1", "https://blog.csdn.net/x/1"));
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        service = new PublicationService(List.of(local, csdn), records, redis);
    }

    @Test
    void successfulPublicationIsPersistedAndReturned() {
        when(records.selectOne(any())).thenReturn(null);
        when(records.insert(any())).thenAnswer(invocation -> {
            invocation.<BlogPublishRecord>getArgument(0).setId(1L);
            return 1;
        });

        PublishResult result = service.publish(command, "local");

        assertThat(result.externalId()).isEqualTo("post-1");
        verify(records).updateById(argThat(record -> "SUCCEEDED".equals(record.getStatus())));
    }

    @Test
    void successfulDuplicateDoesNotCallPublisherAgain() {
        BlogPublishRecord existing = new BlogPublishRecord();
        existing.setStatus("SUCCEEDED");
        existing.setExternalId("post-9");
        existing.setUrl("/blog/9");
        when(records.selectOne(any())).thenReturn(existing);

        PublishResult result = service.publish(command, "local");

        assertThat(result.externalId()).isEqualTo("post-9");
        verify(local, never()).publish(any());
        verify(records, never()).insert(any());
    }

    @Test
    void unknownPlatformIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.publish(command, "unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        verifyNoInteractions(records);
    }

    @Test
    void csdnDailyLimitRejectsNewSyncWith429() {
        when(records.selectOne(any())).thenReturn(null);
        when(records.selectCount(any())).thenReturn((long) PublicationService.CSDN_DAILY_SYNC_LIMIT);

        assertThatThrownBy(() -> service.publish(command, "csdn"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(429);
        verify(csdn, never()).publish(any());
    }

    @Test
    void csdnCooldownRejectsRapidConsecutiveSyncWith429() {
        when(records.selectOne(any())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.publish(command, "csdn"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(429);
    }

    @Test
    void alreadySyncedPostDoesNotConsumeDailyQuota() {
        BlogPublishRecord existing = new BlogPublishRecord();
        existing.setStatus("SUCCEEDED");
        existing.setExternalId("csdn-9");
        existing.setUrl("https://blog.csdn.net/x/9");
        when(records.selectOne(any())).thenReturn(existing);

        PublishResult result = service.publish(command, "csdn");

        assertThat(result.externalId()).isEqualTo("csdn-9");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any());
        verify(records, never()).selectCount(any());
    }

    @Test
    void findsOnlyDurablySucceededPublicationWithoutCallingPublisher() {
        BlogPublishRecord succeeded = new BlogPublishRecord();
        succeeded.setStatus("SUCCEEDED");
        succeeded.setExternalId("csdn-9");
        succeeded.setUrl("https://blog.csdn.net/x/9");
        when(records.selectOne(any())).thenReturn(succeeded);

        PublishResult result = service.findSucceeded(42L, -7L, "csdn");

        assertThat(result).isEqualTo(new PublishResult("csdn", "csdn-9", "https://blog.csdn.net/x/9"));
        verify(csdn, never()).publish(any());
    }

    @Test
    void doesNotTreatFailedOrIncompletePublicationAsSucceeded() {
        BlogPublishRecord failed = new BlogPublishRecord();
        failed.setStatus("FAILED");
        failed.setExternalId("csdn-9");
        BlogPublishRecord missingExternalId = new BlogPublishRecord();
        missingExternalId.setStatus("SUCCEEDED");
        when(records.selectOne(any())).thenReturn(failed, missingExternalId);

        assertThat(service.findSucceeded(42L, -7L, "csdn")).isNull();
        assertThat(service.findSucceeded(42L, -7L, "csdn")).isNull();
        verify(csdn, never()).publish(any());
    }
}
