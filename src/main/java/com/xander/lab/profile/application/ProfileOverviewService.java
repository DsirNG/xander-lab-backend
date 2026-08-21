package com.xander.lab.profile.application;

import com.xander.lab.billing.application.BillingService;
import com.xander.lab.identity.api.TokenResponse;
import com.xander.lab.identity.application.AuthService;
import com.xander.lab.publishing.application.BlogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 构建当前用户个人页所需的账户与统计概览。 */
@Service
@RequiredArgsConstructor
public class ProfileOverviewService {

    private final AuthService authService;
    private final BillingService billingService;
    private final BlogService blogService;

    /** 聚合当前用户信息、积分余额和可管理博客数量。 */
    public ProfileOverview getOverview(Long userId) {
        TokenResponse.UserInfo user = authService.getUserInfo(userId);
        long points = billingService.balance(userId);
        long blogCount = blogService.countManagedBlogs(userId);
        return new ProfileOverview(user, points, blogCount);
    }

    /** 个人页首屏只读响应，不包含积分流水与博客正文。 */
    public record ProfileOverview(TokenResponse.UserInfo user, long points, long blogCount) {
    }
}
