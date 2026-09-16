package com.xander.lab.integration.mcp.csdn.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.publishing.domain.PublishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C-end command for synchronizing one owned blog post to CSDN. */
@RestController
@RequestMapping("/api/blog/posts")
@RequiredArgsConstructor
public class CsdnBlogSyncController {
    private final CsdnBlogSyncService service;

    @PostMapping("/{postId}/sync/csdn")
    public Result<PublishResult> sync(@PathVariable Long postId) {
        return Result.success(service.sync(UserContext.getUserId(), postId));
    }
}
