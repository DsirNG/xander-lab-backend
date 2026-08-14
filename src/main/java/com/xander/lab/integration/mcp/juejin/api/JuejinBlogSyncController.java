package com.xander.lab.integration.mcp.juejin.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
import com.xander.lab.publishing.domain.PublishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** C-end command for synchronizing one owned blog post to Juejin. */
@RestController
@RequestMapping("/api/blog/posts")
@RequiredArgsConstructor
public class JuejinBlogSyncController {
    private final JuejinBlogSyncService service;

    /**
     * Body: {"juejinCategoryId": "6809637773935386632", "juejinTagIds": ["6809640407484334093"]}
     * Both fields are optional; the publisher falls back to the frontend category.
     */
    @PostMapping("/{postId}/sync/juejin")
    public Result<PublishResult> sync(@PathVariable Long postId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        List<String> tagIds = null;
        if (body != null && body.get("juejinTagIds") instanceof List<?> tags) {
            tagIds = tags.stream().map(String::valueOf).toList();
        }
        String categoryId = body == null ? null : body.get("juejinCategoryId") == null
                ? null : String.valueOf(body.get("juejinCategoryId"));
        return Result.success(service.sync(UserContext.getUserId(), postId, categoryId, tagIds));
    }
}