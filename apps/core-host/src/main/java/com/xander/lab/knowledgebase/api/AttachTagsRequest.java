package com.xander.lab.knowledgebase.api;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 给文件打标签。标签名不存在会顺手建出来，前端不必先建标签再打标。
 *
 * @param names 标签名列表；空串与重复项会被忽略
 */
public record AttachTagsRequest(
        @Size(max = 50, message = "一次最多提交 50 个标签")
        List<String> names) {
}
