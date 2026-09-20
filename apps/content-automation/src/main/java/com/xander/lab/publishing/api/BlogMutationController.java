package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.publishing.application.BlogMutationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated publishing mutations extracted into content-automation. */
@RestController
@RequestMapping("/api/blog/posts")
public class BlogMutationController {

    private final BlogMutationService service;

    public BlogMutationController(BlogMutationService service) {
        this.service = service;
    }

    @PutMapping("/{id}")
    public Result<BlogPostVO> update(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id,
            @RequestBody(required = false) BlogPostDTO request) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        try {
            return Result.success(service.update(userId, id, request));
        } catch (IllegalArgumentException e) {
            return mapError(e);
        }
    }

    @PatchMapping("/{id}/status")
    public Result<BlogPostVO> updateStatus(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id,
            @RequestBody(required = false) BlogPostStatusUpdateRequest request) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        try {
            return Result.success(service.updateStatus(userId, id, request));
        } catch (IllegalArgumentException e) {
            return mapError(e);
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        try {
            service.moveToTrash(userId, id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return mapError(e);
        }
    }

    @DeleteMapping("/{id}/permanent")
    public Result<Void> permanentlyDelete(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        try {
            service.permanentlyDelete(userId, id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return mapError(e);
        }
    }

    private static <T> Result<T> mapError(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "请求无效" : e.getMessage();
        if (message.contains("无权")) {
            return Result.forbidden(message);
        }
        if (message.contains("不存在")) {
            return Result.notFound(message);
        }
        return Result.badRequest(message);
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
