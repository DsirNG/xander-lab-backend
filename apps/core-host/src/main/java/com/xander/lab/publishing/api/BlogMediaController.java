package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.common.PageData;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.publishing.application.AgentImageVO;
import com.xander.lab.publishing.application.BlogMediaService;
import com.xander.lab.publishing.domain.BlogMediaAsset;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


@RestController
@RequestMapping("/api/blog/media")
@RequiredArgsConstructor
public class BlogMediaController {
    private final BlogMediaService mediaService;

    /** Keeps the same-package controller contract for callers while the HTTP read route lives in content-automation. */
    public Result<PageData<AgentImageVO>> getAgentImages(int page, int size) {
        return Result.success(mediaService.getAgentImages(UserContext.getUserId(), page, size));
    }

    @PostMapping("/images")
    public Result<BlogMediaAsset> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.success(mediaService.uploadImage(UserContext.getUserId(), file));
    }
}
