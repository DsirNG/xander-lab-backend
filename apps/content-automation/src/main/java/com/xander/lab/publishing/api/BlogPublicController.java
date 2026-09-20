package com.xander.lab.publishing.api;

import com.xander.lab.common.PageData;
import com.xander.lab.common.Result;
import com.xander.lab.publishing.application.BlogPublicReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public publishing reads extracted into the content-automation deployment. */
@RestController
@RequestMapping("/api/blog/posts")
public class BlogPublicController {

    private final BlogPublicReadService service;

    public BlogPublicController(BlogPublicReadService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageData<BlogPostVO>> list(
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "") String category,
            @RequestParam(required = false, defaultValue = "") String tag,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        return Result.success(service.listPublished(search, category, tag, page, size));
    }

    @GetMapping("/recent")
    public Result<List<BlogPostVO>> recent(
            @RequestParam(required = false, defaultValue = "5") int limit) {
        return Result.success(service.recent(limit));
    }

    @GetMapping("/{id}")
    public Result<BlogPostVO> find(@PathVariable long id) {
        BlogPostVO post = service.findPublished(id);
        return post == null ? Result.notFound("文章不存在") : Result.success(post);
    }
}
