package com.xander.lab.identity.application;

import com.xander.lab.identity.api.WechatCredentialView;
import com.xander.lab.identity.domain.WechatCredential;
import com.xander.lab.identity.infrastructure.WechatCredentialMapper;
import com.xander.lab.identity.infrastructure.wechat.WechatCredentialCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * 微信小程序登录凭据：管理台读写（加密落库、密钥永不下发）与登录链路的明文读取。
 *
 * <p>单行语义（固定 id=1）：无数据行时微信登录通道关闭，不影响其它登录方式。
 * identity 拥有微信通道，其它模块（如 admin）只能通过本服务操作凭据，
 * 不得直接读写 {@code wechat_credential} 表。
 */
@Service
@RequiredArgsConstructor
public class WechatCredentialService {

    private static final Long CREDENTIAL_ID = 1L;
    private static final int APP_ID_MAX = 100;
    private static final int APP_SECRET_MAX = 200;

    private final WechatCredentialMapper mapper;
    private final WechatCredentialCipher cipher;

    /** 管理台读取：仅返回 AppID、是否已配置与更新时间，AppSecret 永不下发。 */
    @Transactional(readOnly = true)
    public WechatCredentialView getAdminView() {
        WechatCredential row = mapper.selectById(CREDENTIAL_ID);
        if (row == null) {
            return new WechatCredentialView(null, false, null);
        }
        return new WechatCredentialView(row.getAppId(),
                StringUtils.hasText(row.getEncryptedAppSecret()), row.getUpdatedAt());
    }

    /**
     * 管理台保存凭据：AppID 与 AppSecret 必填，密钥加密落库。
     * 加密密钥未配置时返回 400（管理台可修复状态），避免无密钥写入无法解密的数据。
     */
    @Transactional
    public WechatCredentialView save(String appId, String appSecret) {
        if (!StringUtils.hasText(appId)) {
            throw new IllegalArgumentException("AppID 不能为空");
        }
        if (appId.trim().length() > APP_ID_MAX) {
            throw new IllegalArgumentException("AppID 过长");
        }
        if (!StringUtils.hasText(appSecret)) {
            throw new IllegalArgumentException("AppSecret 不能为空");
        }
        if (appSecret.trim().length() > APP_SECRET_MAX) {
            throw new IllegalArgumentException("AppSecret 过长");
        }
        if (!cipher.isReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "微信加密密钥未配置，无法保存凭据");
        }
        String encrypted = cipher.encrypt(appSecret.trim());
        WechatCredential row = mapper.selectById(CREDENTIAL_ID);
        if (row == null) {
            row = new WechatCredential();
            row.setId(CREDENTIAL_ID);
            row.setAppId(appId.trim());
            row.setEncryptedAppSecret(encrypted);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException e) {
                // 并发首写冲突：另一请求已插入 id=1，转为更新，避免残留重复行。
                updateCredential(appId.trim(), encrypted);
            }
        } else {
            updateCredential(appId.trim(), encrypted);
        }
        return getAdminView();
    }

    /** 管理台清空凭据：删除配置行，微信登录通道随即关闭。 */
    @Transactional
    public void clear() {
        mapper.deleteById(CREDENTIAL_ID);
    }

    /**
     * 登录链路读取（identity 内部使用）：返回明文凭据。
     *
     * @return 可用凭据；null 表示未配置（登录通道关闭）
     * @throws IllegalStateException 加密密钥缺失或与库中密文不匹配时抛出，由调用方归一化为业务错误
     */
    public ResolvedCredential resolve() {
        WechatCredential row = mapper.selectById(CREDENTIAL_ID);
        if (row == null || !StringUtils.hasText(row.getEncryptedAppSecret())) {
            return null;
        }
        String secret = cipher.decrypt(row.getEncryptedAppSecret());
        return new ResolvedCredential(row.getAppId(), secret);
    }

    private void updateCredential(String appId, String encryptedAppSecret) {
        WechatCredential row = new WechatCredential();
        row.setId(CREDENTIAL_ID);
        row.setAppId(appId);
        row.setEncryptedAppSecret(encryptedAppSecret);
        mapper.updateById(row);
    }

    /** 已解密的微信凭据值对象（仅限身份模块内部流转，绝不进入响应或日志）。 */
    public record ResolvedCredential(String appId, String appSecret) {
    }
}
