package com.xander.lab.operations.mysqlreliability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** V9 §87 Reliability metrics backed by deployment-owned backup evidence. */
@Component
public class MySqlReliabilityMetrics {

    public MySqlReliabilityMetrics(MeterRegistry meterRegistry, MySqlReliabilityState state) {
        Gauge.builder("backup_success", state, MySqlReliabilityState::backupSuccess)
                .description("最近一次 MySQL backup 是否成功；-1 表示没有证据")
                .register(meterRegistry);
        Gauge.builder("last_backup_age", state, MySqlReliabilityState::lastBackupAgeSeconds)
                .description("最近一次 MySQL backup 的年龄，单位秒；-1 表示没有证据")
                .register(meterRegistry);
        Gauge.builder("binlog_lag", state, MySqlReliabilityState::binlogLagSeconds)
                .description("MySQL binlog replication lag，单位秒；单实例或没有证据为 -1")
                .register(meterRegistry);
        Gauge.builder("restore_drill_date", state, MySqlReliabilityState::restoreDrillDate)
                .description("最近一次 MySQL restore drill 的 UTC 日期起点 Unix 秒；-1 表示没有证据")
                .register(meterRegistry);
        Gauge.builder("mysql_reliability_evidence_available", state,
                        MySqlReliabilityState::evidenceAvailable)
                .description("MySQL 可靠性证据文件是否可读")
                .register(meterRegistry);
    }
}
