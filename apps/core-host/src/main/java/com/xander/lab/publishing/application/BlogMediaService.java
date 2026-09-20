package com.xander.lab.publishing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.common.PageData;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.content.api.StoredImage;
import com.xander.lab.integration.api.OssPort;
import com.xander.lab.integration.api.UploadResponse;
import com.xander.lab.publishing.domain.BlogMediaAsset;
import com.xander.lab.publishing.infrastructure.BlogMediaAssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 媒体库：用户上传与智能体生成图片的登记、查询与复用。
 *
 * <p>跨域调用方一律走 {@link MediaAssetPort}，本类在边界上把 MyBatis 实体
 * {@link BlogMediaAsset} 映射成 {@link MediaAssetRef}。实体留在 {@code publishing.domain}
 * 不出去 —— 它带 {@code @TableName} / {@code @TableId}，进契约就等于把 MyBatis 拖进去。</p>
 *
 * <p>本类还保留两个<b>只服务同域</b>的方法：{@code BlogMediaController} 直接用具体类型
 * （先例：{@code FileController} 仍注入 {@code OssService}）。所以
 * {@link #getImages} / {@link #getAgentImages} 返回实体与视图对象，不上端口。</p>
 */
@Service
@RequiredArgsConstructor
public class BlogMediaService implements MediaAssetPort {

    private static final Set<String> SUPPORTED_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    /** 提成静态字段：避免在 {@link #findReusableGenerated} 的循环里每次 {@code new} 一个。 */
    private static final ObjectMapper META_MAPPER = new ObjectMapper();

    private final BlogMediaAssetMapper assetMapper;
    private final OssPort ossPort;

    // ------------------------------------------------------------------
    // MediaAssetPort
    // ------------------------------------------------------------------

    @Override
    public MediaAssetRef recordStored(Long userId, StoredImage stored) {
        requireUser(userId);
        if (stored == null || stored.url() == null || stored.contentType() == null
                || !stored.contentType().startsWith("image/")) {
            throw new IllegalArgumentException("无效的智能体图片上传结果");
        }
        BlogMediaAsset asset = new BlogMediaAsset();
        asset.setUserId(userId);
        asset.setSourceType("user_upload");
        asset.setUrl(stored.url());
        asset.setOriginalName(stored.originalName());
        asset.setStoredName(stored.storedName());
        asset.setSize(stored.size());
        asset.setContentType(stored.contentType());
        assetMapper.insert(asset);
        return toRef(asset);
    }

    @Override
    public MediaAssetRef recordGenerated(Long userId, Long taskId, String originalName,
                                         byte[] content, String contentType, String generationMeta) {
        requireUser(userId);
        // 空字节会以 NPE 的形式炸在 ByteArrayInputStream 上，给一条能读的消息。
        if (content == null || content.length == 0) {
            throw new IllegalStateException("智能体生成的图片内容为空");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取智能体生成的图片", e);
        }
        if (image == null) throw new IllegalStateException("智能体生成的图片格式无法识别");

        UploadResponse uploaded = ossPort.upload(content, originalName, contentType, "photos/blog/agent/");
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
        return toRef(asset);
    }

    @Override
    public MediaAssetRef uploadImage(Long userId, byte[] content, String originalName, String contentType) {
        requireUser(userId);
        return toRef(insertUploadedImage(userId, content, originalName, contentType));
    }

    /**
     * 本域内便捷重载，只服务同域的 {@code BlogMediaController}，所以返回**实体**。
     *
     * <p>⚠️ 两个判断的顺序是有意的：先登录、再类型、再大小。类型不需要读字节，
     * 所以可以照原样排在大小前面；大小必须排在 {@code getBytes()} <b>之前</b>，否则一个
     * 100MB 的请求会被整份读进内存才被拒（{@code spring.servlet.multipart.max-file-size}
     * 是 100MB，这里只收 10MB）。端口上因此公开了 {@link MediaAssetPort#MAX_IMAGE_BYTES}，
     * 让跨域调用方（studio）能自己先卡一道。</p>
     */
    public BlogMediaAsset uploadImage(Long userId, MultipartFile file) {
        requireUser(userId);
        if (file == null || file.isEmpty() || !SUPPORTED_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("仅支持 PNG、JPEG、WebP 或 GIF 图片");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取图片内容", e);
        }
        return insertUploadedImage(userId, content, file.getOriginalFilename(), file.getContentType());
    }

    /**
     * 用户上传落库的**唯一**实现体：端口方法与同域重载都走这里，避免两套校验。
     *
     * <p>前置条件：调用方已 {@code requireUser(userId)}。放在调用方是为了让"登录先于参数"
     * 这个顺序在各自入口上看得见，而不是藏进这里。</p>
     */
    private BlogMediaAsset insertUploadedImage(Long userId, byte[] content, String originalName, String contentType) {
        if (content == null || content.length == 0 || !SUPPORTED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("仅支持 PNG、JPEG、WebP 或 GIF 图片");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取图片内容", e);
        }
        // Standard JDK ImageIO usually has no WebP reader. Browsers and the
        // Img2Three relief renderer can consume WebP directly, so validate its
        // RIFF signature instead of advertising support and then rejecting it.
        if (image == null && !("image/webp".equals(contentType) && isWebp(content))) {
            throw new IllegalArgumentException("无法识别图片内容");
        }

        UploadResponse uploaded = ossPort.upload(content, originalName, contentType, "photos/blog/");
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

    @Override
    public MediaAssetRef findOwned(Long userId, Long assetId) {
        requireUser(userId);
        if (assetId == null) return null;
        BlogMediaAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<BlogMediaAsset>()
                .eq(BlogMediaAsset::getId, assetId)
                .eq(BlogMediaAsset::getUserId, userId)
                .last("LIMIT 1"));
        return asset == null ? null : toRef(asset);
    }

    @Override
    public List<MediaAssetRef> listByTask(Long userId, Long taskId) {
        requireUser(userId);
        return assetMapper.selectList(new LambdaQueryWrapper<BlogMediaAsset>()
                        .eq(BlogMediaAsset::getUserId, userId)
                        .eq(BlogMediaAsset::getAgentTaskId, taskId)
                        .orderByAsc(BlogMediaAsset::getId))
                .stream()
                .map(BlogMediaService::toRef)
                .toList();
    }

    /** 返回该用户最近一张插画标题匹配的生成图；损坏或旧版元数据跳过继续扫。 */
    @Override
    public MediaAssetRef findReusableGenerated(Long userId, String title) {
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
                var node = META_MAPPER.readTree(meta);
                if (title.equals(node.path("title").asText())) return toRef(asset);
            } catch (Exception ignored) {
                // Corrupt or legacy meta: skip and keep scanning.
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 同域内方法（BlogMediaController）——返回实体与视图对象，不上端口
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * 实体 → 契约引用的<b>唯一</b>映射点。
     *
     * <p>刻意逐个字段写出而不反射 / 不 {@code BeanUtils.copyProperties}：字段名对不上时
     * 反射版本会静默留 {@code null}，而这里编译期就报错。</p>
     */
    private static MediaAssetRef toRef(BlogMediaAsset asset) {
        return new MediaAssetRef(
                asset.getId(),
                asset.getUserId(),
                asset.getUrl(),
                asset.getOriginalName(),
                asset.getStoredName(),
                asset.getSize(),
                asset.getContentType(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getSourceType(),
                asset.getAgentTaskId(),
                asset.getGenerationMeta(),
                asset.getCreatedAt());
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalStateException("未登录或登录已过期");
        }
    }

    private static boolean isWebp(byte[] content) {
        return content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P';
    }
}
