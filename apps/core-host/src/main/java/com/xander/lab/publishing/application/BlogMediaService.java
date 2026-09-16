package com.xander.lab.publishing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.common.PageData;
import com.xander.lab.integration.api.OssPort;
import com.xander.lab.integration.api.UploadResponse;
import com.xander.lab.publishing.domain.BlogMediaAsset;
import com.xander.lab.publishing.infrastructure.BlogMediaAssetMapper;
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
    private final OssPort ossPort;

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
        // Standard JDK ImageIO usually has no WebP reader. Browsers and the
        // Img2Three relief renderer can consume WebP directly, so validate its
        // RIFF signature instead of advertising support and then rejecting it.
        if (image == null && !("image/webp".equals(file.getContentType()) && isWebp(file))) {
            throw new IllegalArgumentException("无法识别图片内容");
        }

        // 上面已经卡过 10MB，所以整份读进内存是有界的。
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取图片内容", e);
        }
        UploadResponse uploaded = ossPort.upload(
                content, file.getOriginalFilename(), file.getContentType(), "photos/blog/");
        BlogMediaAsset asset = new BlogMediaAsset();
        asset.setUserId(userId);
        asset.setUrl(uploaded.getUrl());
        asset.setOriginalName(uploaded.getOriginalName());
        asset.setStoredName(uploaded.getStoredName());
        asset.setSize(uploaded.getSize());
        asset.setContentType(uploaded.getContentType());
        asset.setWidth(image == null ? null : image.getWidth());
        asset.setHeight(image == null ? null : image.getHeight());
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

        UploadResponse uploaded = ossPort.upload(
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

    /** Persists an already-uploaded agent image as a reusable user-owned media asset. */
    public BlogMediaAsset saveAgentUploadedImage(Long userId, UploadResponse uploaded) {
        requireUser(userId);
        if (uploaded == null || uploaded.getUrl() == null || uploaded.getContentType() == null
                || !uploaded.getContentType().startsWith("image/")) {
            throw new IllegalArgumentException("无效的智能体图片上传结果");
        }
        BlogMediaAsset asset = new BlogMediaAsset();
        asset.setUserId(userId);
        asset.setSourceType("user_upload");
        asset.setUrl(uploaded.getUrl());
        asset.setOriginalName(uploaded.getOriginalName());
        asset.setStoredName(uploaded.getStoredName());
        asset.setSize(uploaded.getSize());
        asset.setContentType(uploaded.getContentType());
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

    /** Returns one agent-generated asset only when it belongs to the requesting user. */
    public BlogMediaAsset getOwnedImage(Long userId, Long assetId) {
        requireUser(userId);
        if (assetId == null) return null;
        return assetMapper.selectOne(new LambdaQueryWrapper<BlogMediaAsset>()
                .eq(BlogMediaAsset::getId, assetId)
                .eq(BlogMediaAsset::getUserId, userId)
                .last("LIMIT 1"));
    }

    /** Returns the most recent agent-generated image of this user whose
     * illustration title matches `title`, so a repeated illustration for a
     * related article is reused instead of regenerated. Never returns assets
     * owned by another user. */
    public BlogMediaAsset findReusableAgentImage(Long userId, String title) {
        requireUser(userId);
        if (title == null || title.isBlank()) return null;
        List<BlogMediaAsset> recent = assetMapper.selectList(new LambdaQueryWrapper<BlogMediaAsset>()
                .eq(BlogMediaAsset::getUserId, userId)
                .eq(BlogMediaAsset::getSourceType, "agent_generated")
                .orderByDesc(BlogMediaAsset::getId)
                .last("LIMIT 100"));
        for (BlogMediaAsset asset : recent) {
            String meta = asset.getGenerationMeta();
            if (meta == null || meta.isBlank()) continue;
            try {
                var node = new ObjectMapper().readTree(meta);
                if (title.equals(node.path("title").asText())) return asset;
            } catch (Exception ignored) {
                // Corrupt or legacy meta: skip and keep scanning.
            }
        }
        return null;
    }

    /** 分页返回当前用户智能体生成的图片（素材库中 sourceType=agent_generated），
     * 供「我的图片」画廊使用。损坏或缺失的生成元数据不阻断展示。 */
    public PageData<AgentImageVO> getAgentImages(Long userId, int page, int size) {
        requireUser(userId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 60);
        Page<BlogMediaAsset> result = assetMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<BlogMediaAsset>()
                        .eq(BlogMediaAsset::getUserId, userId)
                        .eq(BlogMediaAsset::getSourceType, "agent_generated")
                        .orderByDesc(BlogMediaAsset::getId));
        List<AgentImageVO> records = result.getRecords().stream().map(AgentImageVO::from).toList();
        Page<AgentImageVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(records);
        return PageData.of(voPage);
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

    private boolean isWebp(MultipartFile file) {
        try {
            byte[] header = file.getBytes();
            return header.length >= 12
                    && header[0] == 'R'
                    && header[1] == 'I'
                    && header[2] == 'F'
                    && header[3] == 'F'
                    && header[8] == 'W'
                    && header[9] == 'E'
                    && header[10] == 'B'
                    && header[11] == 'P';
        } catch (IOException e) {
            return false;
        }
    }
}
