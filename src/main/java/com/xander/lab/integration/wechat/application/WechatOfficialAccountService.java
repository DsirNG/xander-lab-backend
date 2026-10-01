package com.xander.lab.integration.wechat.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.wechat.WechatOfficialAccountProperties;
import com.xander.lab.integration.wechat.infrastructure.WechatOfficialAccountClient;
import com.xander.lab.integration.wechat.infrastructure.WechatOfficialAccountCredential;
import com.xander.lab.integration.wechat.infrastructure.WechatOfficialAccountCredentialCipher;
import com.xander.lab.integration.wechat.infrastructure.WechatOfficialAccountCredentialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/** Owns user-scoped official-account configuration and token refresh. */
@Service
@RequiredArgsConstructor
public class WechatOfficialAccountService {
    private static final int APP_ID_MAX = 100;
    private static final int APP_SECRET_MAX = 200;
    private static final int ACCOUNT_NAME_MAX = 128;

    private final WechatOfficialAccountProperties properties;
    private final WechatOfficialAccountCredentialMapper mapper;
    private final WechatOfficialAccountCredentialCipher cipher;
    private final WechatOfficialAccountClient client;

    @Transactional(readOnly = true)
    public AccountView status(Long userId) {
        if (!properties.isEnabled()) {
            return new AccountView("UNAVAILABLE", null, null, null);
        }
        WechatOfficialAccountCredential row = find(userId);
        if (row == null || !StringUtils.hasText(row.getAppId())) {
            return new AccountView("NOT_CONFIGURED", null, null, null);
        }
        return new AccountView(row.getStatus(), row.getAccountName(), maskAppId(row.getAppId()),
                row.getLastValidatedAt());
    }

    @Transactional
    public AccountView save(Long userId, String appId, String appSecret, String accountName) {
        ensureEnabled();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        if (!StringUtils.hasText(appId) || appId.trim().length() > APP_ID_MAX) {
            throw new IllegalArgumentException("AppID 不能为空且不能超过 " + APP_ID_MAX + " 个字符");
        }
        if (!StringUtils.hasText(appSecret) || appSecret.trim().length() > APP_SECRET_MAX) {
            throw new IllegalArgumentException("AppSecret 不能为空且不能超过 " + APP_SECRET_MAX + " 个字符");
        }
        if (StringUtils.hasText(accountName) && accountName.trim().length() > ACCOUNT_NAME_MAX) {
            throw new IllegalArgumentException("公众号名称过长");
        }
        if (!cipher.isReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信公众号加密密钥未配置");
        }

        WechatOfficialAccountCredential row = new WechatOfficialAccountCredential();
        row.setUserId(userId);
        row.setAppId(appId.trim());
        row.setEncryptedAppSecret(cipher.encrypt(appSecret.trim()));
        row.setAccountName(StringUtils.hasText(accountName) ? accountName.trim() : null);
        row.setStatus("ACTIVE");
        mapper.upsert(row);
        return status(userId);
    }

    /** Validates the configured AppID/AppSecret by obtaining and persisting an access_token. */
    public AccountView testConnection(Long userId) {
        ensureEnabled();
        WechatOfficialAccountCredential row = requireCredential(userId);
        WechatOfficialAccountClient.AccessToken token = client.getAccessToken(
                row.getAppId(), cipher.decrypt(row.getEncryptedAppSecret()));
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(token.expiresInSeconds() - 60L);
        // The remote token request intentionally happens before this single local update;
        // no database transaction is held while waiting on WeChat.
        mapper.update(null, new UpdateWrapper<WechatOfficialAccountCredential>()
                .eq("user_id", userId)
                .set("encrypted_access_token", cipher.encrypt(token.value()))
                .set("access_token_expires_at", expiresAt)
                .set("status", "ACTIVE")
                .set("last_validated_at", LocalDateTime.now()));
        return status(userId);
    }

    /** Returns a valid token, refreshing it outside any caller transaction when expired. */
    public String requireAccessToken(Long userId) {
        ensureEnabled();
        WechatOfficialAccountCredential row = requireCredential(userId);
        LocalDateTime now = LocalDateTime.now();
        if (StringUtils.hasText(row.getEncryptedAccessToken())
                && row.getAccessTokenExpiresAt() != null
                && row.getAccessTokenExpiresAt().isAfter(now.plusSeconds(30))) {
            return cipher.decrypt(row.getEncryptedAccessToken());
        }
        testConnection(userId);
        return cipher.decrypt(requireCredential(userId).getEncryptedAccessToken());
    }

    @Transactional
    public void disconnect(Long userId) {
        if (userId == null) return;
        mapper.delete(new LambdaQueryWrapper<WechatOfficialAccountCredential>()
                .eq(WechatOfficialAccountCredential::getUserId, userId));
    }

    private WechatOfficialAccountCredential requireCredential(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        WechatOfficialAccountCredential row = find(userId);
        if (row == null || !StringUtils.hasText(row.getAppId())
                || !StringUtils.hasText(row.getEncryptedAppSecret())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先配置微信公众号 AppID 和 AppSecret");
        }
        return row;
    }

    private WechatOfficialAccountCredential find(Long userId) {
        if (userId == null) return null;
        return mapper.selectOne(new LambdaQueryWrapper<WechatOfficialAccountCredential>()
                .eq(WechatOfficialAccountCredential::getUserId, userId)
                .last("LIMIT 1"));
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信公众号同步功能暂未启用");
        }
    }

    private String maskAppId(String appId) {
        if (appId == null || appId.length() <= 8) return appId;
        return appId.substring(0, 4) + "****" + appId.substring(appId.length() - 4);
    }

    public record AccountView(String status, String accountName, String maskedAppId,
                              LocalDateTime lastValidatedAt) {
    }
}
