package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostStatusUpdateRequest;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.api.CategoryVO;
import com.xander.lab.publishing.api.TagVO;
import com.xander.lab.publishing.application.BlogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 博客接口控制器
 *
 * <pre>
 * 接口列表：
 *   GET    /api/blog/posts                 获取文章列表（公开，仅已发布）
 *   POST   /api/blog/posts                 发布/存草稿（publish=false）
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

    @GetMapping("/posts/publish-rhythm")
    public Result<BlogPublishRhythmVO> getMyPublishRhythm() {
        return Result.success(blogService.getWeeklyPublishRhythm(UserContext.getUserId(), null));
    }

    @GetMapping("/posts/manage-stats")
    public Result<BlogManageStatsVO> getManageStats() {
        return Result.success(blogService.getManageStats(UserContext.getUserId(), null));
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

    public record PublishStatus(String status, Long postId) {}
}
