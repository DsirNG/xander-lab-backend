package com.xander.lab.identity.application;

import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.UserMapper;
import com.xander.lab.identity.infrastructure.wechat.WechatMiniAppClient;
import org.springframework.stereotype.Service;

/** 为支付模块即时核验当前用户微信身份并提供本次签名所需 session_key。 */
@Service
public class WechatPaymentIdentityService {
    private final UserMapper userMapper;
    private final WechatMiniAppClient wechatMiniAppClient;

    public WechatPaymentIdentityService(UserMapper userMapper, WechatMiniAppClient wechatMiniAppClient) {
        this.userMapper = userMapper;
        this.wechatMiniAppClient = wechatMiniAppClient;
    }

    /** loginCode 单次使用；session_key 仅在本次调用内存中参与签名，不落库、不记录日志。 */
    public PaymentIdentity resolve(Long userId, String loginCode) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getOpenid() == null || user.getOpenid().isBlank()) {
            throw new IllegalArgumentException("当前账号未绑定微信小程序身份");
        }
        WechatMiniAppClient.WechatSession session;
        try {
            session = wechatMiniAppClient.code2Session(loginCode);
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException(ex.getMessage());
        }
        if (!session.isOk() || session.sessionKey() == null || !user.getOpenid().equals(session.openid())) {
            throw new IllegalArgumentException("微信支付身份校验失败，请重新登录后再试");
        }
        return new PaymentIdentity(user.getOpenid(), session.sessionKey());
    }

    /** 本次支付使用的微信身份值对象。 */
    public record PaymentIdentity(String openid, String sessionKey) {
    }
}
