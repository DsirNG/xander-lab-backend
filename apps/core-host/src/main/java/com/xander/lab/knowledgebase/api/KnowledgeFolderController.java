package com.xander.lab.knowledgebase.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.knowledgebase.application.KnowledgeFolderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 知识库文件夹树的增删改查。返回扁平列表，树由前端按 {@code parentId} 装。 */
@RestController
@RequestMapping("/api/knowledge-base/folders")
@RequiredArgsConstructor
public class KnowledgeFolderController {

    private final KnowledgeFolderService service;

    @GetMapping
    public Result<List<KnowledgeFolderView>> list() {
        return Result.success(service.list(UserContext.getUserId()));
    }

    @PostMapping
    public Result<KnowledgeFolderView> create(@Valid @RequestBody CreateFolderRequest request) {
        return Result.success(service.create(UserContext.getUserId(), request.parentId(), request.name()));
    }

    @PutMapping("/{folderId}/name")
    public Result<KnowledgeFolderView> rename(@PathVariable Long folderId,
                                              @Valid @RequestBody RenameFolderRequest request) {
        return Result.success(service.rename(UserContext.getUserId(), folderId, request.name()));
    }

    /** 移动文件夹。目标在自己的子树里会被拒绝，移动后超过层级上限也会被拒绝。 */
    @PutMapping("/{folderId}/parent")
    public Result<KnowledgeFolderView> move(@PathVariable Long folderId,
                                           @RequestBody MoveFolderRequest request) {
        return Result.success(service.move(UserContext.getUserId(), folderId, request.parentId()));
    }

    /**
     * 删除文件夹。
     *
     * @param recursive 默认 false，只删空文件夹；非空文件夹返回 409。true 时连子文件夹与其中文件一并删除
     * @return 一并删掉的文件数
     */
    @DeleteMapping("/{folderId}")
    public Result<Integer> delete(@PathVariable Long folderId,
                                 @RequestParam(required = false, defaultValue = "false") boolean recursive) {
        return Result.success(service.delete(UserContext.getUserId(), folderId, recursive));
    }
}
