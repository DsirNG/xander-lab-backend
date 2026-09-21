# Local MySQL Reliability Drill（V9 §31 / §61 / §87）

本记录是本地 Docker 依赖环境的可重复证据，不代表生产 RPO/RTO 已达标。生产仍需把
备份任务、指标采集、告警与恢复演练接入部署平台。

## 运行方式

脚本只在指定 MySQL 容器内部读取 `MYSQL_ROOT_PASSWORD`，不会把密码写入参数、日志或
仓库。它对 `xander_lab` 做一致性 dump，在同一容器创建随机临时库，恢复后统计表数，
最后无条件删除临时库和 dump 文件：

```powershell
python scripts/migration/mysql_reliability_drill.py `
  --container dinqor-refactor-mysql `
  --database xander_lab
```

## 最近一次本地结果

检查时间：2026-09-21（UTC）

```json
{
  "backup_success": true,
  "binlog_enabled": true,
  "binlog_format": "ROW",
  "binlog_lag_seconds": null,
  "availability": "single-instance-no-replica",
  "restore_drill_date": "2026-09-21",
  "restored_table_count": 59
}
```

结论：本地 full backup → restore 已验证，binlog 已开启且为 ROW；当前只有单实例、无
replica，因此 `binlog_lag_seconds` 不可计算，不能据此宣称生产 RPO ≤ 5 分钟。`backup_success`
与 `last_backup_age` 的生产连续指标、`binlog_lag` 的复制指标和正式 `restore_drill_date`
仍需由部署侧备份/监控系统写入 §87 Reliability 指标。
