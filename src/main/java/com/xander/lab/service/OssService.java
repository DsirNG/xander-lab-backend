package com.xander.lab.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.xander.lab.dto.upload.UploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * 阿里云 OSS 服务
 * 对应前端 axios 封装中的 upload() 方法
 */
@Slf4j
@Service
public class OssService {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;

    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    @Value("${aliyun.oss.domain}")
    private String domain;

    /**
     * 上传文件到 OSS
     *
     * @param file       文件对象
     * @param pathPrefix 路径前缀（如 avatars/）
     * @return 上传结果
     */
    public UploadResponse upload(MultipartFile file, String pathPrefix) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        
        try {
            String originalName = file.getOriginalFilename();
            String extension = "";
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();
            }
            
            String storedName = UUID.randomUUID().toString() + (extension.isEmpty() ? "" : "." + extension);
            String objectName = pathPrefix + storedName;

            InputStream inputStream = file.getInputStream();
            ossClient.putObject(bucketName, objectName, inputStream);

            String fileUrl = domain + "/" + objectName;
            
            log.info("[OSS] 文件上传成功: {}", fileUrl);

            return UploadResponse.builder()
                    .url(fileUrl)
                    .originalName(originalName)
                    .storedName(storedName)
                    .size(file.getSize())
                    .contentType(file.getContentType())
                    .extension(extension)
                    .build();
        } catch (Exception e) {
            log.error("[OSS] 文件上传异常: {}", e.getMessage());
            throw new RuntimeException("文件上传至云存储失败");
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }
}
