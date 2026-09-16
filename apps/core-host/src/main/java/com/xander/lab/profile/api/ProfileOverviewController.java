package com.xander.lab.profile.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.profile.application.ProfileOverviewService;
import com.xander.lab.profile.application.ProfileOverviewService.ProfileOverview;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供当前登录用户个人页首屏概览。 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileOverviewController {

    private final ProfileOverviewService profileOverviewService;

    /** 返回当前用户资料、积分余额与博客数量。 */
    @GetMapping("/overview")
    public Result<ProfileOverview> overview() {
        return Result.success(profileOverviewService.getOverview(UserContext.getUserId()));
    }
}
