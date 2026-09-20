package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.publishing.application.BlogPublicReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public publishing taxonomy reads extracted into content-automation. */
@RestController
@RequestMapping("/api/blog")
public class BlogTaxonomyController {

    private final BlogPublicReadService service;

    public BlogTaxonomyController(BlogPublicReadService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public Result<List<CategoryVO>> categories() {
        return Result.success(service.categories());
    }

    @GetMapping("/tags")
    public Result<List<TagVO>> tags() {
        return Result.success(service.tags());
    }

    @GetMapping("/tags/popular")
    public Result<List<TagVO>> popularTags(
            @RequestParam(required = false, defaultValue = "8") int limit) {
        return Result.success(service.popularTags(limit));
    }
}
