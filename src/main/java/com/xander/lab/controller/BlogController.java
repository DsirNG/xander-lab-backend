package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.*;
import com.xander.lab.service.BlogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 博客接口控制器
 *
 * <pre>
 * 接口列表：
 *   GET  /api/blog/posts              获取文章列表（支持 search/category/tag 筛选，支持分页）
 *   POST /api/blog/posts              发布文章
 *   GET  /api/blog/posts/recent       获取最新文章（前N条）
 *   GET  /api/blog/posts/{id}         获取文章详情
 *   POST /api/blog/posts/{id}/view    记录文章阅读（含防刷）
 *   GET  /api/blog/categories         获取所有分类（含文章数量）
 *   GET  /api/blog/tags               获取所有标签（含文章数量）
 *   GET  /api/blog/tags/popular       获取热门标签（前N个）
 * </pre>
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class BlogController {

    private final BlogService blogService;

    /**
     * 发布文章
     */
    @PostMapping("/posts")
    public Result<BlogPostVO> publishPost(@RequestBody BlogPostDTO dto,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return Result.success(blogService.createBlog(dto, true, requestId));
    }

    @GetMapping("/posts/publish-status")
    public Result<PublishStatus> getPublishStatus(@RequestParam String requestId) {
        Long postId = blogService.getPublishedPostId(requestId);
        return Result.success(new PublishStatus(postId == null ? "pending" : "published", postId));
    }

    /**
     * 获取文章列表
     *
     * @param search   关键词（可选）
     * @param category 分类ID（可选）
     * @param tag      标签名称（可选）
     * @param page     页码，默认1
     * @param size     页大小，默认10
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

    /**
     * 获取最新文章
     *
     * @param limit 条数，默认5
     */
    @GetMapping("/posts/recent")
    public Result<List<BlogPostVO>> getRecentPosts(
            @RequestParam(required = false, defaultValue = "5") int limit) {
        return Result.success(blogService.getRecentBlogs(limit));
    }

    /**
     * 获取文章详情
     *
     * @param id 文章ID
     */
    @GetMapping("/posts/{id}")
    public Result<BlogPostVO> getPostById(@PathVariable Long id) {
        BlogPostVO vo = blogService.getBlogById(id);
        if (vo == null) {
            return Result.notFound("文章不存在");
        }
        return Result.success(vo);
    }

    /**
     * 记录文章阅读
     * 前端在博客详情页 onLoad 时调用。
     * 后端根据登录状态做去重：
     * - 登录用户按 userId 去重（24h内同一用户同一文章只计数一次）
     * - 未登录用户按 IP 去重（24h内同一IP同一文章只计数一次）
     *
     * @param id 文章ID
     * @return counted=true 有效阅读（已计数），counted=false 冷却期内重复（未计数）
     */
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

    /**
     * 获取所有分类（含文章数量）
     */
    @GetMapping("/categories")
    public Result<List<CategoryVO>> getCategories() {
        return Result.success(blogService.getCategories());
    }

    /**
     * 获取所有标签（含文章数量，按数量降序）
     */
    @GetMapping("/tags")
    public Result<List<TagVO>> getAllTags() {
        return Result.success(blogService.getAllTags());
    }

    /**
     * 获取热门标签
     *
     * @param limit 条数，默认8
     */
    @GetMapping("/tags/popular")
    public Result<List<TagVO>> getPopularTags(
            @RequestParam(required = false, defaultValue = "8") int limit) {
        return Result.success(blogService.getPopularTags(limit));
    }

    /**
     * 阅读记录响应体
     */
    public record ViewResult(boolean counted) {}
    public record PublishStatus(String status, Long postId) {}
}
