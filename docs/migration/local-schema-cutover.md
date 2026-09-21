# 本地共享 Schema 切换（V9 §62 / §63 / §65）

Wave 1 / Wave 2 的数据库契约是同一个 MySQL 实例、`xander_lab` 一个 canonical
schema、多服务运行账号。早期本地 smoke 容器可能已经在同一个 MySQL 实例里留下了
`dinqor` legacy schema；不能直接把 Core 指到空的 `xander_lab`，否则本地用户和已有
登录会话会看起来“消失”。

`scripts/migration/local_schema_cutover.py` 只用于本地 Docker 验证：

- 只接受名字以 `dinqor-` 开头的 MySQL 容器；
- 通过容器内的 `MYSQL_ROOT_PASSWORD` 连接，不把密码写进参数、日志或仓库；
- 用 `INSERT IGNORE` 将 source 中缺失的行合并到 target，不覆盖 target 已有行；
- 永不删除 source，不修改账号密码，不执行 Core 旧权限的 REVOKE；
- Core grant 只对 target 中已经存在的表执行，缺失 legacy 表必须显式允许。

先做 dry-run：

```powershell
python scripts/migration/local_schema_cutover.py `
  --container dinqor-refactor-mysql
```

确认本地基线允许缺失的旧表后执行：

```powershell
python scripts/migration/local_schema_cutover.py `
  --container dinqor-refactor-mysql `
  --apply `
  --apply-core-grants `
  --allow-missing-grant-tables
```

这一步不是 Flyway DDL 迁移。它只解决本地 legacy 数据与 canonical schema 的切换；
schema 仍必须先由独立 `dinqor-migration-runner` 执行，业务服务保持
`spring.flyway.enabled=false`。生产环境不得直接使用这个 helper，生产数据迁移应由
经过评审的 Migration Job 执行并保留备份、校验和与回滚窗口。

完成后，使用 `python scripts/migration/nacos_smoke.py` 验证 Nacos namespace、DataId、
服务注册和 readiness；Core 的 JDBC URL 必须指向 `xander_lab`，旧 `dinqor` 只作为
本地回退源保留。
