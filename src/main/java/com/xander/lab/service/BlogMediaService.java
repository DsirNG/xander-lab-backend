package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.dto.upload.UploadResponse;
import com.xander.lab.entity.BlogMediaAsset;
import com.xander.lab.mapper.BlogMediaAssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BlogMediaService {
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> SUPPORTED_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private final BlogMediaAssetMapper assetMapper;
    private final OssService ossService;

    public BlogMediaAsset uploadImage(Long userId, MultipartFile file) {
        requireUser(userId);
        if (file == null || file.isEmpty() || !SUPPORTED_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("仅支持 PNG、JPEG、WebP 或 GIF 图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }

        BufferedImage image;
        try {
            image = ImageIO.read(file.getInputStream());
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取图片内容");
        }
        if (image == null) throw new IllegalArgumentException("无法识别图片内容");

        UploadResponse uploaded = ossService.upload(file, "photos/blog/");
        BlogMediaAsset asset = new BlogMediaAsset();
        asset.setUserId(userId);
        asset.setUrl(uploaded.getUrl());
        asset.setOriginalName(uploaded.getOriginalName());
        asset.setStoredName(uploaded.getStoredName());
        asset.setSize(uploaded.getSize());
        asset.setContentType(uploaded.getContentType());
        asset.setWidth(image.getWidth());
        asset.setHeight(image.getHeight());
        asset.setSourceType("user_upload");
        assetMapper.insert(asset);
        return asset;
    }

    public BlogMediaAsset saveAgentImage(Long userId, Long taskId, String originalName,
                                         BlogAgentImageClient.GeneratedImage generated, String generationMeta) {
        requireUser(userId);
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(generated.bytes()));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取智能体生成的图片", e);
        }
        if (image == null) throw new IllegalStateException("智能体生成的图片格式无法识别");

        UploadResponse uploaded = ossService.upload(
                generated.bytes(), originalName, generated.contentType(), "photos/blog/agent/");
        BlogMediaAsset asset = new BlogMediaAsset();
        asset.setUserId(userId);
        asset.setAgentTaskId(taskId);
        asset.setSourceType("agent_generated");
        asset.setGenerationMeta(generationMeta);
        asset.setUrl(uploaded.getUrl());
        asset.setOriginalName(uploaded.getOriginalName());
        asset.setStoredName(uploaded.getStoredName());
        asset.setSize(uploaded.getSize());
        asset.setContentType(uploaded.getContentType());
        asset.setWidth(image.getWidth());
        asset.setHeight(image.getHeight());
        assetMapper.insert(asset);
        return asset;
    }

    public List<BlogMediaAsset> getTaskImages(Long userId, Long taskId) {
        requireUser(userId);
        return assetMapper.selectList(new LambdaQueryWrapper<BlogMediaAsset>()
                .eq(BlogMediaAsset::getUserId, userId)
                .eq(BlogMediaAsset::getAgentTaskId, taskId)
                .orderByAsc(BlogMediaAsset::getId));
    }

    public List<BlogMediaAsset> getImages(Long userId, String scope, String keyword) {
        requireUser(userId);
        int limit = "recent".equals(scope) ? 24 : 60;
        LambdaQueryWrapper<BlogMediaAsset> query = new LambdaQueryWrapper<BlogMediaAsset>()
                .eq(BlogMediaAsset::getUserId, userId)
                .orderByDesc(BlogMediaAsset::getCreatedAt)
                .last("LIMIT " + limit);
        if ("gif".equals(scope)) query.eq(BlogMediaAsset::getContentType, "image/gif");
        if (keyword != null && !keyword.isBlank()) query.like(BlogMediaAsset::getOriginalName, keyword.trim());
        return assetMapper.selectList(query);
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalStateException("未登录或登录已过期");
        }
    }
}
