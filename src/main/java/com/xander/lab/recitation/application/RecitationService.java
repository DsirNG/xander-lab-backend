package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.integration.oss.UploadResponse;
import com.xander.lab.recitation.api.CreateRecitationMaterialRequest;
import com.xander.lab.recitation.api.RecitationAttemptView;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.domain.RecitationAttempt;
import com.xander.lab.recitation.domain.RecitationAttemptStatus;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.domain.KnowledgeTestMode;
import com.xander.lab.recitation.domain.KnowledgeType;
import com.xander.lab.recitation.infrastructure.RecitationAttemptMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import com.xander.lab.recitation.infrastructure.RecitationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 背诵材料、录音上传和用户侧任务查询用例。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecitationService {
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "aac", "wav", "m4a", "ogg", "opus", "amr");
    /** 单次列表返回上限；总量另有 {@link #countMaterials(Long)}，避免把截断后的条数当成总数。 */
    private static final int LIST_LIMIT = 100;

    private final RecitationMaterialMapper materialMapper;
    private final RecitationAttemptMapper attemptMapper;
    private final RecitationAligner aligner;
    private final OssService ossService;
    private final RecitationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /** 创建并标准化一份用户确认过的背诵材料。 */
    @Transactional
    public RecitationMaterialView createMaterial(Long userId, CreateRecitationMaterialRequest request) {
        requireUser(userId);
        validateMaterialRequest(request);
        String content = request.content().trim();
        String normalized = aligner.normalize(content);
        if (normalized.isEmpty()) throw new IllegalArgumentException("背诵原文必须包含中文、字母或数字");
        RecitationMaterial material = new RecitationMaterial();
        material.setUserId(userId);
        material.setTitle(request.title().trim());
        material.setContent(content);
        material.setNormalizedContent(normalized);
        KnowledgeType knowledgeType = KnowledgeType.fromNullable(request.knowledgeType());
        material.setKnowledgeType(knowledgeType.name());
        material.setTestMode(KnowledgeTestMode.fromNullable(request.testMode(), knowledgeType).name());
        material.setMasteryScore(java.math.BigDecimal.ZERO);
        material.setMasteryLevel("NEW");
        material.setReviewCount(0);
        material.setCreatedAt(LocalDateTime.now());
        material.setUpdatedAt(material.getCreatedAt());
        materialMapper.insert(material);
        return toView(material);
    }

    /**
     * 个人知识库的筛选条件。
     *
     * <p>搜索、掌握等级和到期过滤都下推到 SQL：这些条件如果只在"最近一批"上做内存过滤，
     * 知识条数超过 {@link #LIST_LIMIT} 之后就会查不到也不报错——对调用方（页面或智能体）
     * 表现为"这条知识不存在"，比直接报错更难发现。</p>
     *
     * @param search       标题或正文的模糊匹配词，空表示不过滤
     * @param masteryLevel 掌握等级精确匹配，空表示不过滤
     * @param dueOnly      只要已到复习时间的条目
     * @param limit        明细条数上限，仅对列表查询有意义
     */
    public record MaterialQuery(String search, String masteryLevel, boolean dueOnly, int limit) {
        /** 不带任何过滤的查询，用于"一共多少条"这类全库统计。 */
        public static MaterialQuery all() {
            return new MaterialQuery(null, null, false, LIST_LIMIT);
        }
    }

    /** 按创建时间倒序列出当前用户的材料。 */
    @Transactional(readOnly = true)
    public List<RecitationMaterialView> listMaterials(Long userId) {
        return searchMaterials(userId, MaterialQuery.all());
    }

    /** 按条件筛选当前用户的材料，创建时间倒序。 */
    @Transactional(readOnly = true)
    public List<RecitationMaterialView> searchMaterials(Long userId, MaterialQuery query) {
        requireUser(userId);
        int limit = Math.min(Math.max(query.limit(), 1), LIST_LIMIT);
        return materialMapper.selectList(materialFilter(userId, query)
                        .orderByDesc(RecitationMaterial::getCreatedAt, RecitationMaterial::getId)
                        .last("LIMIT " + limit))
                .stream().map(this::toView).toList();
    }

    /** 当前用户的知识点总数；列表接口有条数上限，统计口径必须单独查。 */
    @Transactional(readOnly = true)
    public long countMaterials(Long userId) {
        return countMaterials(userId, MaterialQuery.all());
    }

    /** 按同一筛选条件统计条数，让调用方能如实区分"没有匹配"和"匹配了但没全返回"。 */
    @Transactional(readOnly = true)
    public long countMaterials(Long userId, MaterialQuery query) {
        requireUser(userId);
        Long total = materialMapper.selectCount(materialFilter(userId, query));
        return total == null ? 0L : total;
    }

    private LambdaQueryWrapper<RecitationMaterial> materialFilter(Long userId, MaterialQuery query) {
        LambdaQueryWrapper<RecitationMaterial> wrapper = new LambdaQueryWrapper<RecitationMaterial>()
                .eq(RecitationMaterial::getUserId, userId);
        String search = query.search() == null ? "" : query.search().trim();
        if (!search.isEmpty()) {
            wrapper.and(nested -> nested.like(RecitationMaterial::getTitle, search)
                    .or().like(RecitationMaterial::getContent, search));
        }
        String level = query.masteryLevel() == null ? "" : query.masteryLevel().trim().toUpperCase(Locale.ROOT);
        if (!level.isEmpty()) {
            wrapper.eq(RecitationMaterial::getMasteryLevel, level);
        }
        if (query.dueOnly()) {
            wrapper.isNotNull(RecitationMaterial::getNextReviewAt)
                    .le(RecitationMaterial::getNextReviewAt, LocalDateTime.now());
        }
        return wrapper;
    }

    /** 返回一份属于当前用户的材料。 */
    @Transactional(readOnly = true)
    public RecitationMaterialView getMaterial(Long userId, Long materialId) {
        return toView(requireMaterial(userId, materialId));
    }

    /**
     * 先持久化上传意图，再调用 OSS，最后将任务转为待提交；任何上传失败都会留下可审计失败状态。
     */
    public RecitationAttemptView createAttempt(Long userId, Long materialId, MultipartFile file) {
        requireUser(userId);
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "背诵音转文功能尚未启用");
        }
        requireMaterial(userId, materialId);
        validateAudio(file);
        Long attemptId = transactionTemplate.execute(status -> {
            RecitationAttempt attempt = new RecitationAttempt();
            attempt.setUserId(userId);
            attempt.setMaterialId(materialId);
            attempt.setStatus(RecitationAttemptStatus.UPLOADING);
            attempt.setCreatedAt(LocalDateTime.now());
            attempt.setUpdatedAt(attempt.getCreatedAt());
            attemptMapper.insert(attempt);
            return attempt.getId();
        });
        if (attemptId == null) throw new IllegalStateException("无法创建背诵任务");
        try {
            UploadResponse uploaded = ossService.upload(file, "recitations/" + userId + "/");
            transactionTemplate.executeWithoutResult(status -> attemptMapper.update(null,
                    new LambdaUpdateWrapper<RecitationAttempt>()
                            .eq(RecitationAttempt::getId, attemptId)
                            .eq(RecitationAttempt::getUserId, userId)
                            .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.UPLOADING)
                            .set(RecitationAttempt::getAudioUrl, uploaded.getUrl())
                            .set(RecitationAttempt::getStatus, RecitationAttemptStatus.PENDING)
                            .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now())));
        } catch (RuntimeException error) {
            transactionTemplate.executeWithoutResult(status -> attemptMapper.update(null,
                    new LambdaUpdateWrapper<RecitationAttempt>()
                            .eq(RecitationAttempt::getId, attemptId)
                            .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.UPLOADING)
                            .set(RecitationAttempt::getStatus, RecitationAttemptStatus.FAILED)
                            .set(RecitationAttempt::getErrorMessage, "录音上传失败，请稍后重试")
                            .set(RecitationAttempt::getUpdatedAt, LocalDateTime.now())));
            throw error;
        }
        return getAttempt(userId, attemptId);
    }

    /** 查询一条属于当前用户的背诵任务及报告。 */
    @Transactional(readOnly = true)
    public RecitationAttemptView getAttempt(Long userId, Long attemptId) {
        requireUser(userId);
        RecitationAttempt attempt = attemptMapper.selectOne(new LambdaQueryWrapper<RecitationAttempt>()
                .eq(RecitationAttempt::getId, attemptId)
                .eq(RecitationAttempt::getUserId, userId));
        if (attempt == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "背诵任务不存在");
        return toView(attempt);
    }

    /** 列出某材料最近的背诵任务。 */
    @Transactional(readOnly = true)
    public List<RecitationAttemptView> listAttempts(Long userId, Long materialId) {
        requireMaterial(userId, materialId);
        return attemptMapper.selectList(new LambdaQueryWrapper<RecitationAttempt>()
                        .eq(RecitationAttempt::getUserId, userId)
                        .eq(RecitationAttempt::getMaterialId, materialId)
                        .orderByDesc(RecitationAttempt::getCreatedAt, RecitationAttempt::getId)
                        .last("LIMIT 30"))
                .stream().map(this::toView).toList();
    }

    private void validateAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("录音文件不能为空");
        if (file.getSize() > properties.getMaxAudioBytes()) {
            throw new IllegalArgumentException("录音文件不能超过 " + properties.getMaxAudioBytes() / 1024 / 1024 + "MB");
        }
        String name = file.getOriginalFilename();
        String extension = "";
        if (StringUtils.hasText(name) && name.lastIndexOf('.') >= 0) {
            extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        String contentType = file.getContentType();
        if (!AUDIO_EXTENSIONS.contains(extension)
                && (!StringUtils.hasText(contentType) || !contentType.toLowerCase(Locale.ROOT).startsWith("audio/"))) {
            throw new IllegalArgumentException("仅支持 mp3、aac、wav、m4a、ogg、opus 或 amr 录音");
        }
    }

    private void validateMaterialRequest(CreateRecitationMaterialRequest request) {
        if (request == null || !StringUtils.hasText(request.title())) {
            throw new IllegalArgumentException("材料标题不能为空");
        }
        if (request.title().trim().length() > 100) {
            throw new IllegalArgumentException("材料标题不能超过100个字符");
        }
        if (!StringUtils.hasText(request.content())) {
            throw new IllegalArgumentException("背诵原文不能为空");
        }
        if (request.content().trim().length() > 10000) {
            throw new IllegalArgumentException("背诵原文不能超过10000个字符");
        }
    }

    private RecitationMaterial requireMaterial(Long userId, Long materialId) {
        requireUser(userId);
        RecitationMaterial material = materialMapper.selectOne(new LambdaQueryWrapper<RecitationMaterial>()
                .eq(RecitationMaterial::getId, materialId)
                .eq(RecitationMaterial::getUserId, userId));
        if (material == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "背诵材料不存在");
        return material;
    }

    private void requireUser(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
    }

    private RecitationMaterialView toView(RecitationMaterial material) {
        return new RecitationMaterialView(material.getId(), material.getTitle(), material.getContent(),
                material.getNormalizedContent().codePointCount(0, material.getNormalizedContent().length()),
                material.getKnowledgeType(), material.getTestMode(), material.getMasteryScore(),
                material.getMasteryLevel(), material.getReviewCount() == null ? 0 : material.getReviewCount(),
                material.getLastReviewAt(), material.getNextReviewAt(),
                material.getCreatedAt(), material.getUpdatedAt());
    }

    private RecitationAttemptView toView(RecitationAttempt attempt) {
        Object result = null;
        if (StringUtils.hasText(attempt.getResultJson())) {
            try {
                result = objectMapper.readValue(attempt.getResultJson(), Object.class);
            } catch (Exception error) {
                log.warn("Unable to deserialize recitation result, attemptId={}", attempt.getId(), error);
                result = null;
            }
        }
        return new RecitationAttemptView(attempt.getId(), attempt.getMaterialId(), attempt.getStatus(),
                attempt.getAudioUrl(), attempt.getTranscript(), result, attempt.getScore(),
                attempt.getErrorMessage(), attempt.getCreatedAt(), attempt.getUpdatedAt());
    }
}
