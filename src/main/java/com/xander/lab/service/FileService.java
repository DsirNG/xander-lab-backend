package com.xander.lab.service;

import com.xander.lab.dto.upload.UploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文件上传 / 下载服务
 * 对应前端 axios 封装中的 upload() 和 download() 方法
 *
 * <pre>
 * 上传接口：POST /api/upload          单文件上传
 *           POST /api/upload/batch    多文件上传
 * 下载接口：GET  /api/download/{filename}  文件下载
 *           GET  /api/export/excel         导出 Excel（模拟）
 *           GET  /api/export/csv           导出 CSV（模拟）
 * </pre>
 */
@Slf4j
@Service
public class FileService {

    /** 文件存储根目录，从配置文件读取 */
    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    /** 文件访问基础 URL */
    @Value("${file.access-url:/api/download}")
    private String accessUrl;

    /** 允许上传的文件类型 */
    private static final List<String> ALLOWED_TYPES = List.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml",
            "application/pdf",
            "text/plain", "text/csv",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/zip", "application/x-zip-compressed"
    );

    /** 最大文件大小（字节）：50MB */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024L;

    /**
     * 单文件上传
     *
     * @param file 上传的文件
     * @return 上传结果
     */
    public UploadResponse upload(MultipartFile file) throws IOException {
        validateFile(file);

        String originalName = file.getOriginalFilename();
        String extension = getExtension(originalName);
        String storedName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);

        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(uploadPath);

        Path targetPath = uploadPath.resolve(storedName);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        log.info("[File] 文件上传成功：{} → {}", originalName, storedName);

        return UploadResponse.builder()
                .url(accessUrl + "/" + storedName)
                .originalName(originalName)
                .storedName(storedName)
                .size(file.getSize())
                .contentType(file.getContentType())
                .extension(extension)
                .build();
    }

    /**
     * 批量文件上传
     *
     * @param files 文件列表
     * @return 上传结果列表
     */
    public List<UploadResponse> uploadBatch(List<MultipartFile> files) throws IOException {
        List<UploadResponse> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(upload(file));
        }
        return results;
    }

    /**
     * 获取文件的物理路径（供 Controller 读取文件流）
     *
     * @param filename 存储文件名
     * @return 文件路径
     */
    public Path resolveFilePath(String filename) {
        return Paths.get(uploadDir).toAbsolutePath().normalize().resolve(filename);
    }

    /**
     * 生成模拟 CSV 内容（对应前端 download() 测试）
     */
    public byte[] generateCsvContent(String type) {
        StringBuilder sb = new StringBuilder();
        switch (type) {
            case "blog" -> {
                sb.append("ID,标题,分类,作者,发布日期,浏览量\n");
                sb.append("1,Spring Boot 入门指南,后端开发,Xander,2024-01-15,1280\n");
                sb.append("2,React 18 新特性详解,前端开发,Xander,2024-02-20,980\n");
                sb.append("3,MySQL 性能优化实践,数据库,Xander,2024-03-10,756\n");
            }
            case "user" -> {
                sb.append("ID,用户名,昵称,角色,注册时间\n");
                sb.append("1,admin,管理员,ADMIN,2024-01-01\n");
                sb.append("2,xander,Xander,USER,2024-01-02\n");
                sb.append("3,demo,演示用户,GUEST,2024-01-03\n");
            }
            default -> {
                sb.append("导出类型,导出时间\n");
                sb.append(type).append(",").append(java.time.LocalDateTime.now()).append("\n");
            }
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ─── 私有工具方法 ───────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过限制（最大 50MB）");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("不支持的文件类型：" + contentType);
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
