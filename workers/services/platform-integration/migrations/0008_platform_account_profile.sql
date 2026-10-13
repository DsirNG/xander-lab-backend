-- 平台账号的已验证展示资料，仅允许凭据属主读取，不包含 Cookie 或令牌。
ALTER TABLE platform_credential ADD COLUMN account_profile TEXT;
