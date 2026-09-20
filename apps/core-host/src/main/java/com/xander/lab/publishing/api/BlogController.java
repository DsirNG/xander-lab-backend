package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.api.CategoryVO;
import com.xander.lab.publishing.api.TagVO;
import com.xander.lab.publishing.application.BlogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 博客接口控制器
 *
 * <pre>
 * 接口列表：
 *   POST   /api/blog/posts                 发布/存草稿（publish=false）
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

}
