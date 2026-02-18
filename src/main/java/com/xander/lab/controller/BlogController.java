package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.dto.CategoryVO;
import com.xander.lab.dto.PageData;
import com.xander.lab.dto.TagVO;
import com.xander.lab.service.BlogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 博客接口控制器
 *
 * <pre>
 * 接口列表：
 *   GET /api/blog/posts              获取文章列表（支持 search/category/tag 筛选，支持分页）
 *   GET /api/blog/posts/recent       获取最新文章（前N条）
 *   GET /api/blog/posts/{id}         获取文章详情
 *   GET /api/blog/categories         获取所有分类（含文章数量）
 *   GET /api/blog/tags               获取所有标签（含文章数量）
 *   GET /api/blog/tags/popular       获取热门标签（前N个）
 * </pre>
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class BlogController {

    private final BlogService blogService;

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
}
