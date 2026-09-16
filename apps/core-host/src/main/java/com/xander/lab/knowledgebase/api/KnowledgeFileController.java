package com.xander.lab.knowledgebase.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.knowledgebase.application.KnowledgeFileService;
import com.xander.lab.knowledgebase.application.KnowledgeStructureService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文件：上传、检索、维护、知识结构预览与标签关联。
 *
 * <p>大文件走 {@link KnowledgeUploadController} 的分片接口，这里的 {@code POST /upload}
 * 只用于小文件表单直传；两条路最终落到同一段入库逻辑，库里的记录长得一模一样。</p>
 */
@RestController
@RequestMapping("/api/knowledge-base/files")
@RequiredArgsConstructor
public class KnowledgeFileController {

    private final KnowledgeFileService fileService;
    private final KnowledgeStructureService structureService;
    private final KnowledgeTagService tagService;

    /**
     * 表单直传。文件夹必填且不能是根目录。
     *
     * @param tagNames 手动标签，可不传
     */
    @PostMapping("/upload")
    public Result<KnowledgeFileView> upload(@RequestPart("file") MultipartFile file,
                                           @RequestParam Long folderId,
                                           @RequestParam(required = false) List<String> tagNames) {
        return Result.success(fileService.upload(UserContext.getUserId(), folderId, file, tagNames));
    }

    /**
     * 文件检索，全部条件可选、可组合。
     *
     * @param recursive       true 时连同该文件夹的子文件夹一起搜
     * @param tagIds          要求同时具备全部指定标签
     * @param structureStatus PENDING / READY / CHAPTER_ON_DEMAND / UNSUPPORTED / FAILED
     */
    @GetMapping
    public Result<KnowledgePageResult<KnowledgeFileView>> search(
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false, defaultValue = "false") boolean recursive,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<Long> tagIds,
            @RequestParam(required = false) String extension,
            @RequestParam(required = false) String structureStatus,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "20") long size) {
        KnowledgeFileService.FileQuery query = new KnowledgeFileService.FileQuery(
                folderId, recursive, keyword, tagIds, extension, structureStatus, page, size);
        return Result.success(fileService.search(UserContext.getUserId(), query));
    }

    @GetMapping("/{fileId}")
    public Result<KnowledgeFileView> detail(@PathVariable Long fileId) {
        return Result.success(fileService.detail(UserContext.getUserId(), fileId));
    }

    /** 知识结构预览：文件信息 + 章节目录 + 结构图 + 正文开头片段，一次给全。 */
    @GetMapping("/{fileId}/preview")
    public Result<KnowledgeFilePreviewView> preview(@PathVariable Long fileId) {
        return Result.success(fileService.preview(UserContext.getUserId(), fileId));
    }

    /** 抽取出的完整正文。抽不出正文的格式返回 409。 */
    @GetMapping("/{fileId}/content")
    public Result<String> content(@PathVariable Long fileId) {
        return Result.success(fileService.content(UserContext.getUserId(), fileId));
    }

    @GetMapping("/{fileId}/chapters")
    public Result<List<KnowledgeChapterView>> chapters(@PathVariable Long fileId) {
        return Result.success(fileService.chapters(UserContext.getUserId(), fileId));
    }

    /** 重命名。只改展示名，扩展名与对象存储里的键都不动。 */
    @PutMapping("/{fileId}/name")
    public Result<KnowledgeFileView> rename(@PathVariable Long fileId,
                                           @Valid @RequestBody RenameFileRequest request) {
        return Result.success(fileService.rename(UserContext.getUserId(), fileId, request.displayName()));
    }

    @PutMapping("/{fileId}/folder")
    public Result<KnowledgeFileView> move(@PathVariable Long fileId,
                                         @Valid @RequestBody MoveFileRequest request) {
        return Result.success(fileService.move(UserContext.getUserId(), fileId, request.folderId()));
    }

    /** 删除文件及其章节、结构图、正文、标签关联，并删掉对象存储里的原文件。 */
    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@PathVariable Long fileId) {
        fileService.delete(UserContext.getUserId(), fileId);
        return Result.success();
    }

    /** 用库里存着的正文重新识别章节与结构图，不回源下载原文件。 */
    @PostMapping("/{fileId}/reidentify")
    public Result<KnowledgeFileView> reidentify(@PathVariable Long fileId) {
        return Result.success(fileService.reidentify(UserContext.getUserId(), fileId));
    }

    /**
     * 读取结构图。
     *
     * @param chapterId 不传读整本书维度；传了读该章按需生成的那一张
     */
    @GetMapping("/{fileId}/structure")
    public Result<KnowledgeStructureView> structure(@PathVariable Long fileId,
                                                   @RequestParam(required = false) Long chapterId) {
        return Result.success(structureService.read(UserContext.getUserId(), fileId, chapterId));
    }

    /** 整本书维度重新生成结构图。 */
    @PostMapping("/{fileId}/structure/regenerate")
    public Result<KnowledgeStructureView> regenerateStructure(@PathVariable Long fileId) {
        return Result.success(structureService.regenerateForFile(UserContext.getUserId(), fileId));
    }

    /**
     * 按章按需生成结构图：大部头文件上传时不整本出图，用户点进某一章时调这里补上。
     * 已生成过就直接返回现成的，不重复算。
     */
    @PostMapping("/{fileId}/chapters/{chapterId}/structure")
    public Result<KnowledgeStructureView> generateChapterStructure(@PathVariable Long fileId,
                                                                  @PathVariable Long chapterId) {
        return Result.success(structureService.generateForChapter(UserContext.getUserId(), fileId, chapterId));
    }

    /** 追加手动标签。标签名不存在会顺手建出来，前端不必先建标签再打标。 */
    @PostMapping("/{fileId}/tags")
    public Result<List<KnowledgeTagView>> attachTags(@PathVariable Long fileId,
                                                    @Valid @RequestBody AttachTagsRequest request) {
        return Result.success(tagService.attachByNames(UserContext.getUserId(), fileId, request.names()));
    }

    /** 整体替换手动标签，自动标签保持不动。 */
    @PutMapping("/{fileId}/tags")
    public Result<List<KnowledgeTagView>> replaceTags(@PathVariable Long fileId,
                                                      @Valid @RequestBody AttachTagsRequest request) {
        return Result.success(tagService.replaceManual(UserContext.getUserId(), fileId, request.names()));
    }

    @DeleteMapping("/{fileId}/tags/{tagId}")
    public Result<List<KnowledgeTagView>> detachTag(@PathVariable Long fileId, @PathVariable Long tagId) {
        return Result.success(tagService.detach(UserContext.getUserId(), fileId, tagId));
    }
}
