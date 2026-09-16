package com.xander.lab.knowledgebase.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.knowledgebase.application.KnowledgeUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 分片上传与断点续传。
 *
 * <p>标准流程：{@code POST /init} 拿到 uploadId 与分片大小 → 逐片 {@code POST /{uploadId}/chunks/{index}}
 * → {@code POST /{uploadId}/complete} 合并入库。断线后重新 {@code POST /init}（同一文件同一目标文件夹）
 * 会命中幂等键拿回原会话（{@code resumed=true}），再用 {@code GET /{uploadId}} 里的
 * {@code missingChunks} 补传缺的那几片即可。</p>
 *
 * <p>分片索引从 0 开始。除最后一片外每片大小必须正好等于会话的 {@code chunkSize}，
 * 服务端会核对，对不上直接拒收——这样截断的重传会在出问题的那一片被发现，
 * 而不是等到合并时变成一个说不清来源的哈希不一致。</p>
 */
@RestController
@RequestMapping("/api/knowledge-base/uploads")
@RequiredArgsConstructor
public class KnowledgeUploadController {

    private final KnowledgeUploadService service;

    /** 建会话。同一用户重复 init 同一个文件会拿回原会话而不是新建，这是断点续传的入口。 */
    @PostMapping("/init")
    public Result<KnowledgeUploadSessionView> init(@Valid @RequestBody InitUploadRequest request) {
        return Result.success(service.init(UserContext.getUserId(), request));
    }

    /** 查会话进度，含已收分片数与缺失分片列表。 */
    @GetMapping("/{uploadId}")
    public Result<KnowledgeUploadSessionView> status(@PathVariable String uploadId) {
        return Result.success(service.status(UserContext.getUserId(), uploadId));
    }

    /** 上传一片。重复上传同一片是允许的，后到的覆盖先到的。 */
    @PostMapping("/{uploadId}/chunks/{chunkIndex}")
    public Result<KnowledgeUploadSessionView> uploadChunk(@PathVariable String uploadId,
                                                         @PathVariable Integer chunkIndex,
                                                         @RequestPart("chunk") MultipartFile chunk) {
        return Result.success(service.uploadChunk(UserContext.getUserId(), uploadId, chunkIndex, chunk));
    }

    /**
     * 合并入库并做知识化处理。接口幂等：会话已完成时直接返回上次生成的文件，不会重复入库。
     * 整文件哈希核不上会作废会话并返回 409，需要重新 init。
     */
    @PostMapping("/{uploadId}/complete")
    public Result<KnowledgeFileView> complete(@PathVariable String uploadId,
                                             @Valid @RequestBody(required = false) CompleteUploadRequest request) {
        return Result.success(service.complete(UserContext.getUserId(), uploadId,
                request == null ? null : request.tagNames()));
    }

    /** 主动放弃会话：置 ABORTED、删分片记录、清本地暂存。 */
    @DeleteMapping("/{uploadId}")
    public Result<Void> abort(@PathVariable String uploadId) {
        service.abort(UserContext.getUserId(), uploadId);
        return Result.success();
    }
}
