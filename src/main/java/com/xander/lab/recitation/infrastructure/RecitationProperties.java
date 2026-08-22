package com.xander.lab.recitation.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 背诵音转文任务开关、上传限制和工作器节奏。 */
@Data
@Component
@ConfigurationProperties(prefix = "recitation")
public class RecitationProperties {
    private boolean enabled = false;
    private long maxAudioBytes = 20L * 1024 * 1024;
    private int workerBatchSize = 10;
    private int staleClaimSeconds = 120;
}
