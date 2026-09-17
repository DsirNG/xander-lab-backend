package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.StoredImage;
import com.xander.lab.integration.api.OssPort;
import com.xander.lab.integration.api.UploadResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/** Validates and uploads user-provided agent images and documents. */
@Service
public class AgentAttachmentService {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final Set<String> FILE_EXTENSIONS = Set.of(
            "pdf", "txt", "md", "json", "html", "xml", "doc", "docx", "rtf", "odt",
            "ppt", "pptx", "csv", "xls", "xlsx", "tsv", "java", "js", "jsx", "ts", "tsx", "py", "css");

    private final OssPort ossPort;
    private final MediaAssetPort mediaAssetPort;

    public AgentAttachmentService(OssPort ossPort, MediaAssetPort mediaAssetPort) {
        this.ossPort = ossPort;
        this.mediaAssetPort = mediaAssetPort;
    }

    /** Uploads one bounded, model-supported attachment outside any database transaction. */
    public AgentAttachment upload(Long userId, MultipartFile file) {
        if (userId == null) throw new IllegalArgumentException("未登录或登录已过期");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("附件不能为空");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("单个附件不能超过20MB");
        String name = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename().trim() : "attachment";
        String extension = extension(name);
        if (!IMAGE_EXTENSIONS.contains(extension) && !FILE_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("暂不支持该附件格式");
        }
        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType().trim() : "application/octet-stream";
        // 大小上面已经卡过 20MB，所以整份读进内存是有界的。
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取附件内容", e);
        }
        UploadResponse uploaded = ossPort.upload(content, name, contentType, "agent/" + userId + "/");
        if (mediaAssetPort != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            var asset = mediaAssetPort.recordStored(userId, new StoredImage(
                    uploaded.getUrl(), uploaded.getOriginalName(), uploaded.getStoredName(),
                    uploaded.getSize(), uploaded.getContentType()));
            return new AgentAttachment(uploaded.getUrl(), name, contentType, uploaded.getSize(), asset.id());
        }
        return new AgentAttachment(uploaded.getUrl(), name, contentType, uploaded.getSize());
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
