# 掘金（Juejin）MCP 发布与扫码授权生产部署清单

本次改动：掘金 MCP 模块（`/api/mcp/juejin` 发布 + `/api/publishing/juejin/authorization`
扫码授权 + 凭据 AES-GCM 加密入库）、前端掘金授权面板与六语言文案。以下按顺序执行。

## 1. 数据库（先做）

在线上库（xander_lab）执行 `src/main/resources/db/juejin_platform_credential_schema.sql`：

```sql
CREATE TABLE IF NOT EXISTS `juejin_platform_credential` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '凭据记录 ID',
  `user_id` BIGINT NOT NULL COMMENT '本应用用户 ID；唯一键约束，每用户至多一行',
  `encrypted_cookie` MEDIUMTEXT NOT NULL COMMENT 'AES-GCM 加密的登录凭据包（Cookie 头、x-secsdk-csrf-token、aid、uuid、用户资料 JSON）',
  `juejin_username` VARCHAR(128) NULL COMMENT '掘金账号昵称（明文展示列）',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE=可用；EXPIRED=登录态失效需重新扫码授权',
  `authorized_at` DATETIME NOT NULL COMMENT '最近一次成功授权时间',
  `last_validated_at` DATETIME NOT NULL COMMENT '最近一次凭据有效性校验时间',
  `created_at` DATETIME NOT NULL COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_juejin_credential_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='加密的按用户存储的掘金授权凭据';
```

该语句幂等（CREATE TABLE IF NOT EXISTS），未执行前不升级后端，否则扫码授权报错无法保存凭据。

再执行 `src/main/resources/db/juejin_category_tag_catalog_schema.sql` 建目录字典表：

```sql
CREATE TABLE IF NOT EXISTS `juejin_category` (...);  -- 分类目录：category_id 主键、名称、排序
CREATE TABLE IF NOT EXISTS `juejin_tag` (...);       -- 标签目录：tag_id 主键、名称、来源分类
```

两张表由发布链路按需从 `tag_api/v1/query_category_list` 与 `tag_api/v1/query_tag_list`
全量同步（默认 24 小时新鲜度窗口，见 `JUEJIN_CATALOG_FRESHNESS_HOURS`），
用于发布时把本地分类名/标签名匹配成掘金侧 ID；未命中的分类/标签回落默认值
（前端分类 `6809637767543259144`、前端标签 `6809640407484334093`）。
跳过建表只影响名称匹配（回落到默认分类/标签），不阻塞发布。

## 2. 后端环境变量（本次新增）

`docker-compose.yml` 已内置对应行，默认 `false`，用 `.env.production` / 环境变量显式启用：

```bash
# 必填：模块与发布开关
MCP_JUEJIN_ENABLED=true
JUEJIN_PUBLISH_ENABLED=true
# 必填：自定密钥（Base64 编码的 32 字节随机值），缺失或非法时启用即启动失败
JUEJIN_CREDENTIAL_ENCRYPTION_KEY=$(openssl rand -base64 32)
```

可选（有合理默认值，可不配）：

```bash
JUEJIN_API_BASE_URL=https://api.juejin.cn   # 默认同上
JUEJIN_DEFAULT_AID=2608                     # 掘金 web 端固定应用 ID，默认同上
JUEJIN_CONNECT_TIMEOUT_MILLIS=10000         # 连接超时（毫秒）
JUEJIN_READ_TIMEOUT_MILLIS=30000            # 读超时（毫秒）
JUEJIN_CATALOG_FRESHNESS_HOURS=24           # 分类/标签目录新鲜度窗口（小时），过期后在发布链路触发全量同步
```

说明：

- `JUEJIN_CREDENTIAL_ENCRYPTION_KEY` 是**自定的加密密钥**，不是掘金颁发的任何凭证；
  与 `CSDN_CREDENTIAL_ENCRYPTION_KEY` 分开存储、分开管理，严禁提交 git。
  更换密钥会使已存凭据无法解密，需重新扫码授权。
- 掘金没有像 CSDN bizapi 那样的开放平台 partner key；发布与授权全部基于扫码登录
  采集的 Cookie，**无需申请任何应用 ID 或密钥**。
- Selenium 复用现有 `selenium` 容器（默认 `http://selenium:4444/wd/hub`），无需新增服务。
- `JUEJIN_SELENIUM_URL` / `JUEJIN_AUTHORIZATION_TIMEOUT_SECONDS`（默认 60s）字段后端已支持，
  但 `application.yml` 暂未映射环境变量占位符，当前阶段使用内置默认值即可。
- 若沿用旧部署脚本（`all_in_one_deploy.sh` / `deploy-aliyun.sh`），需自行注入上述变量，
  脚本默认不会带 `MCP_JUEJIN_ENABLED`（历史事故根因之一，参见 blog-content-plan 部署清单）。

## 3. 前端部署

重新构建部署前端：新增 `JuejinAuthorizationPanel`（二维码/排队/倒计时/断连）、
`/api/mcp/juejin` 端点行与六语言 `profile.juejin.*` 文案。

## 4. 验证

- `curl http://<host>:30002/health` 返回 200。
- 个人设置页（/profile）出现「掘金自动同步」面板，点击「连接掘金」弹扫码二维码，
  扫码成功后面板显示已连接；当前无其他用户占用槽位时二维码直接出现，否则排队。
- 数据库：`select user_id, juejin_username, status from juejin_platform_credential;`
  出现 ACTIVE 行；`encrypted_cookie` 为密文，不得出现明文 Cookie。
- 后端日志 `[Juejin]`：扫码成功有凭据采集记录；用户资料接口失败时降级为仅存 Cookie 并打 WARN。
- 发布验证：掘金 MCP 工具或发文页「同步至掘金」发布草稿成功，掘金个人主页可见。

## 5. 回滚

- 仅停用：重新部署时去掉 `MCP_JUEJIN_ENABLED`（或置 false）即停用；已存凭据不影响，后续可再启用。
- 表不删除：`juejin_platform_credential` 幂等建表，对现有业务无副作用；如需清理须先确认无引用。