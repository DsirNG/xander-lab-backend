package com.xander.lab.identity.application;

import com.xander.lab.identity.infrastructure.UserMapper;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 订阅权益写入口；支付模块不得绕过它直接更新用户表。 */
@Service
public class SubscriptionEntitlementService {
    /** 产品等级从低到高固定为 FREE < PLUS < PRO < ULTRA。 */
    private static final Set<String> PAID_TIERS = Set.of("PLUS", "PRO", "ULTRA");
    private final UserMapper userMapper;

    public SubscriptionEntitlementService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 在当前有效期尾部累加购买周期。调用方必须先完成订单幂等状态迁移。 */
    public void grant(Long userId, String tier, int durationDays) {
        if (!PAID_TIERS.contains(tier) || durationDays < 1) {
            throw new IllegalArgumentException("订阅商品权益配置无效");
        }
        if (userMapper.grantSubscription(userId, tier, durationDays) != 1) {
            throw new IllegalStateException("订阅权益发放失败");
        }
    }
}
