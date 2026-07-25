package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.*;
import com.xander.lab.service.BlogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 博客接口控制器
 *
 * <pre>
 * 接口列表：
 *   GET    /api/blog/posts                 获取文章列表（公开，仅已发布）
 *   POST   /api/blog/posts                 发布/存草稿（publish=false）
 *   GET    /api/blog/posts/mine            我的文章管理列表
 *   GET    /api/blog/posts/mine/{id}       我的文章详情（含草稿/回收站）
 *   PUT    /api/blog/posts/{id}            更新文章
 *   PATCH  /api/blog/posts/{id}/status     变更状态（草稿/发布/回收站）
 *   DELETE /api/blog/posts/{id}            软删除（进入回收站）
 *   DELETE /api/blog/posts/{id}/permanent  彻底删除（仅回收站）
 * </pre>
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class BlogController {

    private final BlogService blogService;

    /**
     * 发布文章或保存草稿（DTO.publish=false 时为草稿）
     */
    @PostMapping("/posts")
    public Result<BlogPostVO> publishPost(@RequestBody BlogPostDTO dto,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        boolean publish = dto.getPublish() == null || Boolean.TRUE.equals(dto.getPublish());
        return Result.success(blogService.createBlog(dto, publish, requestId));
    }

    @GetMapping("/posts/publish-status")
    public Result<PublishStatus> getPublishStatus(@RequestParam String requestId) {
        Long postId = blogService.getPublishedPostId(requestId);
        return Result.success(new PublishStatus(postId == null ? "pending" : "published", postId));
    }

    /**
     * 我的文章列表（管理端）
     * status: 不传=草稿+已发布；0=草稿；1=已发布；-1=回收站
     */
    @GetMapping("/posts/mine")
    public Result<PageData<BlogPostVO>> getMyPosts(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        try {
            return Result.success(blogService.getMyBlogs(status, search, page, size));
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage());
        }
    }

    @GetMapping("/posts/mine/{id}")
    public Result<BlogPostVO> getMyPostById(@PathVariable Long id) {
        BlogPostVO vo = blogService.getManagedBlogById(UserContext.getUserId(), id);
        if (vo == null) {
            return Result.notFound("文章不存在");
        }
        return Result.success(vo);
    }

    @PutMapping("/posts/{id}")
    public Result<BlogPostVO> updatePost(@PathVariable Long id, @RequestBody BlogPostDTO dto) {
        try {
            return Result.success(blogService.updateBlogFromDto(id, dto));
        } catch (IllegalArgumentException e) {
            return mapBlogError(e);
        }
    }

    @PatchMapping("/posts/{id}/status")
    public Result<BlogPostVO> updatePostStatus(
            @PathVariable Long id,
            @RequestBody BlogPostStatusUpdateRequest request) {
        try {
            return Result.success(blogService.updateBlogStatus(id, request == null ? null : request.getStatus()));
        } catch (IllegalArgumentException e) {
            return mapBlogError(e);
        }
    }

    @DeleteMapping("/posts/{id}")
    public Result<Void> softDeletePost(@PathVariable Long id) {
        try {
            blogService.deleteBlog(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return mapBlogError(e);
        }
    }

    @DeleteMapping("/posts/{id}/permanent")
    public Result<Void> permanentlyDeletePost(@PathVariable Long id) {
        try {
            blogService.permanentlyDeleteBlog(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return mapBlogError(e);
        }
    }

    /**
     * 获取文章列表
     */
    @GetMapping("/posts")
    public Result<PageData<BlogPostVO>> getPosts(
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "") String category,
            @RequestParam(required = false, defaultValue = "") String tag,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        return Result.success(blogService.getBlogs(search, category, tag, page, size));
    }

    @GetMapping("/posts/recent")
    public Result<List<BlogPostVO>> getRecentPosts(
            @RequestParam(required = false, defaultValue = "5") int limit) {
        return Result.success(blogService.getRecentBlogs(limit));
    }

    @GetMapping("/posts/{id}")
    public Result<BlogPostVO> getPostById(@PathVariable Long id) {
        BlogPostVO vo = blogService.getBlogById(id);
        if (vo == null) {
            return Result.notFound("文章不存在");
        }
        return Result.success(vo);
    }

    @PostMapping("/posts/{id}/view")
    public Result<ViewResult> recordView(
            @PathVariable Long id,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        Long userId = UserContext.getUserId();
        String ip = UserContext.getClientIp();
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }

        boolean counted = blogService.recordView(id, userId, ip, userAgent);
        return Result.success(new ViewResult(counted));
    }

    @GetMapping("/categories")
    public Result<List<CategoryVO>> getCategories() {
        return Result.success(blogService.getCategories());
    }

    @GetMapping("/tags")
    public Result<List<TagVO>> getAllTags() {
        return Result.success(blogService.getAllTags());
    }

    @GetMapping("/tags/popular")
    public Result<List<TagVO>> getPopularTags(
            @RequestParam(required = false, defaultValue = "8") int limit) {
        return Result.success(blogService.getPopularTags(limit));
    }

    private <T> Result<T> mapBlogError(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "请求无效" : e.getMessage();
        if (message.contains("未登录")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
        }
        if (message.contains("无权")) {
            return Result.forbidden(message);
        }
        if (message.contains("不存在")) {
            return Result.notFound(message);
        }
        return Result.badRequest(message);
    }

    public record ViewResult(boolean counted) {}
    public record PublishStatus(String status, Long postId) {}
}
