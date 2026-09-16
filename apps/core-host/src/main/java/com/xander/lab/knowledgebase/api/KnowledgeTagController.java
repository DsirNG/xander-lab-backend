package com.xander.lab.knowledgebase.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.knowledgebase.application.KnowledgeTagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签词表的增删改查。
 *
 * <p>文件与标签的关联在 {@link KnowledgeFileController} 上（{@code /files/{fileId}/tags}），
 * 这里只管词表本身。</p>
 */
@RestController
@RequestMapping("/api/knowledge-base/tags")
@RequiredArgsConstructor
public class KnowledgeTagController {

    private final KnowledgeTagService service;

    /** 列出全部标签，附带每个标签的引用次数。 */
    @GetMapping
    public Result<List<KnowledgeTagView>> list() {
        return Result.success(service.list(UserContext.getUserId()));
    }

    @PostMapping
    public Result<KnowledgeTagView> create(@Valid @RequestBody CreateTagRequest request) {
        return Result.success(service.create(UserContext.getUserId(),
                request.name(), request.color(), request.description()));
    }

    /** 局部更新：字段为 null 表示不动它，空串表示清空（色值与描述）。 */
    @PutMapping("/{tagId}")
    public Result<KnowledgeTagView> update(@PathVariable Long tagId,
                                          @Valid @RequestBody UpdateTagRequest request) {
        return Result.success(service.update(UserContext.getUserId(), tagId,
                request.name(), request.color(), request.description()));
    }

    /** 删除标签会解除它在所有文件上的关联，文件本身不受影响。 */
    @DeleteMapping("/{tagId}")
    public Result<Void> delete(@PathVariable Long tagId) {
        service.delete(UserContext.getUserId(), tagId);
        return Result.success();
    }
}
