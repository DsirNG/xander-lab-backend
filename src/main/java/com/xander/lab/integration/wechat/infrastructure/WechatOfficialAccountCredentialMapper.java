package com.xander.lab.integration.wechat.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** Persistence port for one user's official-account credential. */
@Mapper
public interface WechatOfficialAccountCredentialMapper extends BaseMapper<WechatOfficialAccountCredential> {

    /** Upserts the user-scoped row so concurrent saves converge on one credential. */
    @Insert("""
            INSERT INTO wechat_official_account_credential
                (user_id, app_id, encrypted_app_secret, account_name, status,
                 encrypted_access_token, access_token_expires_at, last_validated_at)
            VALUES
                (#{userId}, #{appId}, #{encryptedAppSecret}, #{accountName}, #{status},
                 #{encryptedAccessToken}, #{accessTokenExpiresAt}, #{lastValidatedAt})
            AS new
            ON DUPLICATE KEY UPDATE
                app_id = new.app_id,
                encrypted_app_secret = new.encrypted_app_secret,
                account_name = new.account_name,
                status = new.status,
                encrypted_access_token = new.encrypted_access_token,
                access_token_expires_at = new.access_token_expires_at,
                last_validated_at = new.last_validated_at
            """)
    int upsert(WechatOfficialAccountCredential credential);
}
