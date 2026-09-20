package com.xander.lab.publishing.api;

import com.xander.lab.common.PageData;
import com.xander.lab.common.Result;
import com.xander.lab.publishing.application.BlogPrivateReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated private publishing reads extracted into content-automation. */
@RestController
@RequestMapping("/api/blog/posts")
public class BlogPrivateReadController {

    private final BlogPrivateReadService service;

    public BlogPrivateReadController(BlogPrivateReadService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    public Result<PageData<BlogPostVO>> listMine(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        try {
            return Result.success(service.listMine(userId, status, search, page, size));
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage());
        }
    }

    @GetMapping("/mine/{id}")
    public Result<BlogPostVO> findMine(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        BlogPostVO post = service.findMine(userId, id);
        return post == null ? Result.notFound("文章不存在") : Result.success(post);
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long userId = Long.parseLong(header.trim());
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
