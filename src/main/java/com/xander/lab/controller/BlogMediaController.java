package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.entity.BlogMediaAsset;
import com.xander.lab.service.BlogMediaService;
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

    @PostMapping("/images")
    public Result<BlogMediaAsset> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.success(mediaService.uploadImage(UserContext.getUserId(), file));
    }
}
