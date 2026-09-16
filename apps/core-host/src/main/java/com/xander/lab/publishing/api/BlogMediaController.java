package com.xander.lab.publishing.api;

import com.xander.lab.common.PageData;
import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
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

import java.util.List;

@RestController
@RequestMapping("/api/blog/media")
@RequiredArgsConstructor
public class BlogMediaController {
    private final BlogMediaService mediaService;

    @GetMapping("/images")
    public Result<List<BlogMediaAsset>> getImages(
            @RequestParam(defaultValue = "recent") String scope,
            @RequestParam(required = false) String keyword) {
        return Result.success(mediaService.getImages(UserContext.getUserId(), scope, keyword));
    }

    @GetMapping("/images/agent")
    public Result<PageData<AgentImageVO>> getAgentImages(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int size) {
        return Result.success(mediaService.getAgentImages(UserContext.getUserId(), page, size));
    }

    @PostMapping("/images")
    public Result<BlogMediaAsset> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.success(mediaService.uploadImage(UserContext.getUserId(), file));
    }
}
