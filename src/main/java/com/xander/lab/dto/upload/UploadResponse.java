package com.xander.lab.dto.upload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件上传响应体
 * 对应前端 axios 封装中的 upload() 方法返回值
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {

    /** 文件访问 URL */
    private String url;

    /** 原始文件名 */
    private String originalName;

    /** 存储文件名（UUID 重命名后） */
    private String storedName;

    /** 文件大小（字节） */
    private long size;

    /** MIME 类型 */
    private String contentType;

    /** 文件扩展名 */
    private String extension;
}
