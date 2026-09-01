package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 完成一次分片上传：服务端按序合并分片、校验哈希、入库并做知识化处理。
 *
 * <p>接口是幂等的——会话已完成时直接返回上次生成的文件，不会重复入库。</p>
 *
 * @param tagNames 上传时一并提交的手动标签，可为空；与自动生成的标签互不覆盖
 */
public record CompleteUploadRequest(
        @Size(max = 50, message = "一次最多提交 50 个标签")
        List<String> tagNames) {
}
