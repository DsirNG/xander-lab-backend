package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.dto.upload.UploadResponse;
import com.xander.lab.service.FileService;
import com.xander.lab.service.OssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件上传 / 下载控制器
 * 完整对应前端 axios 封装中的 upload() 和 download() 方法
 *
 * <pre>
 * 上传接口：
 *   POST /api/upload              单文件上传（multipart/form-data，字段名 file）
 *   POST /api/upload/batch        多文件上传（字段名 files）
 *
 * 下载接口：
 *   GET  /api/download/{filename} 按文件名下载已上传文件
 *   GET  /api/export/csv          导出 CSV（前端 download() 测试用）
 *   GET  /api/export/excel        导出 Excel（前端 download() 测试用）
 * </pre>
 *
 * 前端 axios 封装对应用法：
 * <pre>
 *   // 单文件上传（带进度）
 *   upload('/api/upload', file, { onProgress: (p) => console.log(p + '%') });
 *
 *   // 批量上传
 *   upload('/api/upload/batch', [file1, file2], { fieldName: 'files' });
 *
 *   // 下载文件
 *   download('/api/export/csv', { filename: 'blogs.csv', params: { type: 'blog' } });
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final OssService ossService;

    // ─────────────────────────────────────────────
    // 上传接口 (阿里云 OSS)
    // ─────────────────────────────────────────────

    /**
     * OSS 文件上传
     * POST /api/upload/oss?type=avatar
     * 
     * @param file 文件
     * @param type 类型: avatar, photo, video (对应配置中的路径前缀)
     */
    @PostMapping("/api/upload/oss")
    public Result<UploadResponse> uploadToOss(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "photo") String type) {
        // 根据类型获取预设路径
        String pathPrefix = switch (type) {
            case "avatar" -> "avatars/";
            case "video" -> "videos/";
            default -> "photos/";
        };
        
        try {
            UploadResponse response = ossService.upload(file, pathPrefix);
            return Result.success(response);
        } catch (Exception e) {
            return Result.error("OSS上传失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 上传接口 (本地存储 - 保持备份)
    // ─────────────────────────────────────────────

    /**
     * 单文件上传 (本地)
     * 前端调用：upload('/api/upload', file, { onProgress })
     *
     * @param file 上传的文件（multipart/form-data，字段名 file）
     * @return 文件访问 URL 等信息
     */
    @PostMapping("/api/upload")
    public Result<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        try {
            UploadResponse response = fileService.upload(file);
            return Result.success(response);
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage());
        } catch (IOException e) {
            log.error("[Upload] 文件上传失败", e);
            return Result.error("文件上传失败：" + e.getMessage());
        }
    }

    /**
     * 批量文件上传
     * 前端调用：upload('/api/upload/batch', [file1, file2], { fieldName: 'files' })
     *
     * @param files 文件列表（字段名 files）
     * @return 上传结果列表
     */
    @PostMapping("/api/upload/batch")
    public Result<List<UploadResponse>> uploadBatch(
            @RequestParam("files") List<MultipartFile> files) {
        try {
            List<UploadResponse> responses = fileService.uploadBatch(files);
            return Result.success(responses);
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage());
        } catch (IOException e) {
            log.error("[Upload] 批量上传失败", e);
            return Result.error("批量上传失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 下载接口
    // ─────────────────────────────────────────────

    /**
     * 按文件名下载已上传的文件
     * 前端调用：download('/api/download/xxx.png', { filename: 'my-image.png' })
     *
     * @param filename 存储文件名
     * @return 文件流（Blob）
     */
    @GetMapping("/api/download/{filename:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            Path filePath = fileService.resolveFilePath(filename);
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            log.error("[Download] 文件读取失败：{}", filename, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 导出 CSV
     * 前端调用：download('/api/export/csv', { filename: 'export.csv', params: { type: 'blog' } })
     *
     * @param type 导出类型（blog / user）
     * @return CSV 文件流
     */
    @GetMapping("/api/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(defaultValue = "blog") String type) {
        byte[] content = fileService.generateCsvContent(type);
        String filename = type + "_export.csv";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length))
                .body(content);
    }

    /**
     * 导出 Excel（模拟，实际返回 CSV 格式，生产环境可接入 EasyExcel）
     * 前端调用：download('/api/export/excel', { filename: 'report.xlsx', params: { type: 'blog' } })
     *
     * @param type 导出类型
     * @return 文件流
     */
    @GetMapping("/api/export/excel")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(defaultValue = "blog") String type) {
        // 模拟数据：实际项目中使用 EasyExcel 生成真实 xlsx
        byte[] content = fileService.generateCsvContent(type);
        String filename = type + "_export.xlsx";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length))
                .body(content);
    }
}
