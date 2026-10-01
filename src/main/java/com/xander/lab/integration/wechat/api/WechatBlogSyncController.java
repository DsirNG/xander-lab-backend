package com.xander.lab.integration.wechat.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.wechat.application.WechatBlogSyncService;
import com.xander.lab.publishing.domain.PublishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User API for manually saving an owned article into the WeChat draft box. */
@RestController
@RequestMapping("/api/blog/posts")
@RequiredArgsConstructor
public class WechatBlogSyncController {
    private final WechatBlogSyncService service;

    @PostMapping("/{postId}/sync/wechat")
    public Result<PublishResult> sync(@PathVariable Long postId) {
        return Result.success(service.sync(UserContext.getUserId(), postId));
    }
}
