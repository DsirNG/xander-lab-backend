package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JuejinPlatformCredentialMapper extends BaseMapper<JuejinPlatformCredential> {

    /**
     * Atomic insert-or-update keyed by the user-scoped unique key. Concurrent saves for the same
     * user converge on a single row instead of racing on a read-then-write.
     */
    @Insert("""
            INSERT INTO juejin_platform_credential
                (user_id, encrypted_cookie, juejin_username, status, authorized_at, last_validated_at)
            VALUES
                (#{userId}, #{encryptedCookie}, #{juejinUsername}, #{status}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            AS new
            ON DUPLICATE KEY UPDATE
                encrypted_cookie = new.encrypted_cookie,
                juejin_username = new.juejin_username,
                status = new.status,
                authorized_at = new.authorized_at,
                last_validated_at = new.last_validated_at
            """)
    int upsert(JuejinPlatformCredential credential);
}
