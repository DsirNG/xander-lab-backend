package com.xander.lab.integration.mcp.juejin.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
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
     * Body: {"juejinCategoryId": "6809637767543259144", "juejinTagIds": ["6809640407484334093"]}
     * Both fields are optional; a category name or numeric id is accepted (unmatched names fall
     * back to the frontend category) and, when no tag ids are given, the local post's tag names
     * are resolved to Juejin tag ids via the synced category/tag catalog.
     */
    @PostMapping("/{postId}/sync/juejin")
    public Result<BlogSyncResult> sync(@PathVariable Long postId,
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