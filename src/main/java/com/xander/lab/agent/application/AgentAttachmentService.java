package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.integration.oss.UploadResponse;
import com.xander.lab.publishing.application.BlogMediaService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

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

    private final OssService ossService;
    private final BlogMediaService mediaService;

    public AgentAttachmentService(OssService ossService, BlogMediaService mediaService) {
        this.ossService = ossService;
        this.mediaService = mediaService;
    }

    /** Compatibility constructor for focused tests that upload documents only. */
    public AgentAttachmentService(OssService ossService) {
        this(ossService, null);
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
        UploadResponse uploaded = ossService.upload(file, "agent/" + userId + "/");
        if (mediaService != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            var asset = mediaService.saveAgentUploadedImage(userId, uploaded);
            return new AgentAttachment(uploaded.getUrl(), name, contentType, uploaded.getSize(), asset.getId());
        }
        return new AgentAttachment(uploaded.getUrl(), name, contentType, uploaded.getSize());
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
