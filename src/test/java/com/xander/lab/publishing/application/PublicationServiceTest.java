package com.xander.lab.publishing.application;

import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import com.xander.lab.publishing.infrastructure.BlogPublishRecord;
import com.xander.lab.publishing.infrastructure.BlogPublishRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PublicationServiceTest {
    private final BlogPublishRecordMapper records = mock(BlogPublishRecordMapper.class);
    private final BlogPublisher local = mock(BlogPublisher.class);
    private PublicationService service;
    private final PublishCommand command = new PublishCommand(7L, 42L, "标题", "摘要", "正文", "backend", List.of("Java"));

    @BeforeEach
    void setUp() {
        when(local.platform()).thenReturn("local");
        when(local.publish(any())).thenReturn(new PublishResult("local", "post-1", "/blog/1"));
        service = new PublicationService(List.of(local), records);
    }

    @Test
    void successfulPublicationIsPersistedAndReturned() {
        when(records.selectOne(any())).thenReturn(null);
        when(records.insert(any())).thenAnswer(invocation -> { invocation.<BlogPublishRecord>getArgument(0).setId(1L); return 1; });

        PublishResult result = service.publish(command, "local");

        assertThat(result.externalId()).isEqualTo("post-1");
        verify(records).updateById(argThat(record -> "SUCCEEDED".equals(record.getStatus())));
    }

    @Test
    void successfulDuplicateDoesNotCallPublisherAgain() {
        BlogPublishRecord existing = new BlogPublishRecord();
        existing.setStatus("SUCCEEDED"); existing.setExternalId("post-9"); existing.setUrl("/blog/9");
        when(records.selectOne(any())).thenReturn(existing);

        PublishResult result = service.publish(command, "local");

        assertThat(result.externalId()).isEqualTo("post-9");
        verifyNoInteractions(local);
        verify(records, never()).insert(any());
    }

    @Test
    void unknownPlatformIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.publish(command, "csdn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        verifyNoInteractions(records);
    }
}
