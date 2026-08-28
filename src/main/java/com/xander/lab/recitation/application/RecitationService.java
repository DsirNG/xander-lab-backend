package com.xander.lab.recitation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.integration.oss.UploadResponse;
import com.xander.lab.recitation.api.CreateRecitationMaterialRequest;
import com.xander.lab.recitation.api.RecitationAttemptView;
import com.xander.lab.recitation.api.RecitationMaterialView;
import com.xander.lab.recitation.api.UpdateRecitationMaterialRequest;
import com.xander.lab.recitation.domain.ArchiveFilter;
import com.xander.lab.recitation.domain.KnowledgeQuizAttempt;
import com.xander.lab.recitation.domain.KnowledgeQuizSession;
import com.xander.lab.recitation.domain.RecitationAttempt;
import com.xander.lab.recitation.domain.RecitationAttemptStatus;
import com.xander.lab.recitation.domain.RecitationMaterial;
import com.xander.lab.recitation.domain.KnowledgeTestMode;
import com.xander.lab.recitation.domain.KnowledgeType;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizAttemptMapper;
import com.xander.lab.recitation.infrastructure.KnowledgeQuizSessionMapper;
import com.xander.lab.recitation.infrastructure.RecitationAttemptMapper;
import com.xander.lab.recitation.infrastructure.RecitationMaterialMapper;
import com.xander.lab.recitation.infrastructure.RecitationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.UUID;

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
    private final KnowledgeQuizAttemptMapper quizAttemptMapper;
    private final KnowledgeQuizSessionMapper quizSessionMapper;
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
     * @param archive      归档可见范围，默认只看未归档
     */
    public record MaterialQuery(String search, String masteryLevel, boolean dueOnly, int limit,
                                ArchiveFilter archive) {
        public MaterialQuery {
            if (archive == null) archive = ArchiveFilter.ACTIVE;
        }

        /** 不显式指定归档范围时只看在用的知识，与页面和智能体的默认口径一致。 */
        public MaterialQuery(String search, String masteryLevel, boolean dueOnly, int limit) {
            this(search, masteryLevel, dueOnly, limit, ArchiveFilter.ACTIVE);
        }

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
        // 归档条件同样下推：归档的知识如果还出现在默认列表和到期复习里，归档这个动作就等于没生效。
        switch (query.archive()) {
            case ACTIVE -> wrapper.isNull(RecitationMaterial::getArchivedAt);
            case ARCHIVED -> wrapper.isNotNull(RecitationMaterial::getArchivedAt);
            case ALL -> { }
        }
        return wrapper;
    }

    /** 返回一份属于当前用户的材料。 */
    @Transactional(readOnly = true)
    public RecitationMaterialView getMaterial(Long userId, Long materialId) {
        return toView(requireMaterial(userId, materialId));
    }

    /**
     * 修改一条已有知识：只改传了的字段。
     *
     * <p>正文或测试方式发生实质变化时会重置掌握度（分数归零、等级回到 NEW、清掉下次复习时间），
     * 因为原来的分数衡量的是改动前的文本和改动前的考法。不重置的话，把正文整段换掉之后
     * 这条知识仍然显示"已掌握、30 天后再复习"——那是一个没有出处的数字，
     * 复习计划也会把一条其实从没测过的内容一直排在最后。已完成的测试次数与最近测试时间作为
     * 历史保留，不清零。</p>
     */
    public RecitationMaterialView updateMaterial(Long userId, Long materialId,
                                                 UpdateRecitationMaterialRequest request) {
        RecitationMaterial material = requireMaterial(userId, materialId);
        if (request == null) return toView(material);
        LambdaUpdateWrapper<RecitationMaterial> update = new LambdaUpdateWrapper<RecitationMaterial>()
                .eq(RecitationMaterial::getId, materialId)
                .eq(RecitationMaterial::getUserId, userId);
        boolean changed = false;

        if (StringUtils.hasText(request.title())) {
            String title = request.title().trim();
            if (title.length() > 100) throw new IllegalArgumentException("材料标题不能超过100个字符");
            if (!title.equals(material.getTitle())) {
                update.set(RecitationMaterial::getTitle, title);
                material.setTitle(title);
                changed = true;
            }
        }

        boolean contentReplaced = false;
        if (request.content() != null) {
            String content = request.content().trim();
            if (content.isEmpty()) throw new IllegalArgumentException("背诵原文不能为空");
            if (content.length() > 10000) throw new IllegalArgumentException("背诵原文不能超过10000个字符");
            String normalized = aligner.normalize(content);
            if (normalized.isEmpty()) throw new IllegalArgumentException("背诵原文必须包含中文、字母或数字");
            // 以归一化文本判断"实质变化"：只调标点或空白不该让掌握记录白费。
            contentReplaced = !normalized.equals(material.getNormalizedContent());
            if (contentReplaced || !content.equals(material.getContent())) {
                update.set(RecitationMaterial::getContent, content)
                        .set(RecitationMaterial::getNormalizedContent, normalized);
                material.setContent(content);
                material.setNormalizedContent(normalized);
                changed = true;
            }
        }

        boolean testModeReplaced = false;
        if (request.knowledgeType() != null || request.testMode() != null) {
            KnowledgeType type = request.knowledgeType() == null
                    ? KnowledgeType.valueOf(material.getKnowledgeType())
                    : KnowledgeType.fromNullable(request.knowledgeType());
            // 只改类型不给考法时按新类型自动匹配：否则背诵改成概念之后，
            // 页面还会拿着 AUDIO_RECITATION 让用户对着概念题录音。
            KnowledgeTestMode mode = KnowledgeTestMode.fromNullable(request.testMode(), type);
            if (!type.name().equals(material.getKnowledgeType())) {
                update.set(RecitationMaterial::getKnowledgeType, type.name());
                material.setKnowledgeType(type.name());
                changed = true;
            }
            if (!mode.name().equals(material.getTestMode())) {
                update.set(RecitationMaterial::getTestMode, mode.name());
                material.setTestMode(mode.name());
                testModeReplaced = true;
                changed = true;
            }
        }

        if (contentReplaced || testModeReplaced) {
            update.set(RecitationMaterial::getMasteryScore, java.math.BigDecimal.ZERO)
                    .set(RecitationMaterial::getMasteryLevel, "NEW")
                    .set(RecitationMaterial::getNextReviewAt, null);
            material.setMasteryScore(java.math.BigDecimal.ZERO);
            material.setMasteryLevel("NEW");
            material.setNextReviewAt(null);
        }
        if (!changed) return toView(material);
        transactionTemplate.executeWithoutResult(status -> materialMapper.update(null, update));
        return toView(material);
    }

    /**
     * 归档或恢复一条知识。
     *
     * <p>归档是删除之外的另一个出口：内容还想留着，但不想它继续出现在列表、到期复习和智能体检索里。
     * 因为可逆，所以它是"我暂时不学这个了"的正确动作；真要清掉数据才用
     * {@link #deleteMaterial(Long, Long)}。</p>
     */
    public RecitationMaterialView archiveMaterial(Long userId, Long materialId, boolean archived) {
        RecitationMaterial material = requireMaterial(userId, materialId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime archivedAt = archived ? now : null;
        transactionTemplate.executeWithoutResult(status -> materialMapper.update(null,
                new LambdaUpdateWrapper<RecitationMaterial>()
                        .eq(RecitationMaterial::getId, materialId)
                        .eq(RecitationMaterial::getUserId, userId)
                        .set(RecitationMaterial::getArchivedAt, archivedAt)));
        material.setArchivedAt(archivedAt);
        return toView(material);
    }
    /**
     * 彻底删除一条知识，连带它的录音任务与测验留档。
     *
     * <p>先删库再尽力删 OSS 音频：删除音频失败不能让整个删除失败，否则用户会卡在
     * "删不掉"的状态；反过来先删音频再删库，删库失败就会留下一条指向已消失音频的记录。
     * 删不掉的音频只记日志，等对象存储的生命周期规则回收。</p>
     */
    public void deleteMaterial(Long userId, Long materialId) {
        requireMaterial(userId, materialId);
        List<String> audioUrls = attemptMapper
                .selectList(new LambdaQueryWrapper<RecitationAttempt>()
                        .select(RecitationAttempt::getAudioUrl)
                        .eq(RecitationAttempt::getUserId, userId)
                        .eq(RecitationAttempt::getMaterialId, materialId))
                .stream().map(RecitationAttempt::getAudioUrl).filter(StringUtils::hasText).toList();
        transactionTemplate.executeWithoutResult(status -> {
            quizAttemptMapper.delete(new LambdaQueryWrapper<KnowledgeQuizAttempt>()
                    .eq(KnowledgeQuizAttempt::getUserId, userId)
                    .eq(KnowledgeQuizAttempt::getMaterialId, materialId));
            quizSessionMapper.delete(new LambdaQueryWrapper<KnowledgeQuizSession>()
                    .eq(KnowledgeQuizSession::getUserId, userId)
                    .eq(KnowledgeQuizSession::getMaterialId, materialId));
            attemptMapper.delete(new LambdaQueryWrapper<RecitationAttempt>()
                    .eq(RecitationAttempt::getUserId, userId)
                    .eq(RecitationAttempt::getMaterialId, materialId));
            materialMapper.delete(new LambdaQueryWrapper<RecitationMaterial>()
                    .eq(RecitationMaterial::getId, materialId)
                    .eq(RecitationMaterial::getUserId, userId));
        });
        audioUrls.forEach(ossService::deleteByUrlQuietly);
    }

    /**
     * 先持久化上传意图，再调用 OSS，最后将任务转为待提交；任何上传失败都会留下可审计失败状态。
     */
    public RecitationAttemptView createAttempt(Long userId, Long materialId, MultipartFile file) {
        return createAttempt(userId, materialId, file, null);
    }

    /**
     * 创建录音任务；同一用户重复使用同一个客户端请求标识时复用第一次创建的任务，避免重复上传与计费。
     */
    public RecitationAttemptView createAttempt(Long userId, Long materialId, MultipartFile file,
                                               String clientRequestId) {
        requireUser(userId);
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "背诵音转文功能尚未启用");
        }
        requireMaterial(userId, materialId);
        validateAudio(file);
        String requestId = normalizeClientRequestId(clientRequestId);
        RecitationAttempt existing = findAttemptByRequestId(userId, requestId);
        if (existing != null) {
            if (!materialId.equals(existing.getMaterialId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "录音请求标识已用于另一条知识");
            }
            return toView(existing);
        }
        Long attemptId;
        try {
            attemptId = transactionTemplate.execute(status -> {
                RecitationAttempt attempt = new RecitationAttempt();
                attempt.setUserId(userId);
                attempt.setMaterialId(materialId);
                attempt.setClientRequestId(requestId);
                attempt.setStatus(RecitationAttemptStatus.UPLOADING);
                attemptMapper.insert(attempt);
                return attempt.getId();
            });
        } catch (DuplicateKeyException duplicate) {
            // 两个相同请求并发到达时，唯一索引只允许一个上传；输掉竞争的请求复用已落库任务。
            RecitationAttempt raced = findAttemptByRequestId(userId, requestId);
            if (raced == null) throw duplicate;
            if (!materialId.equals(raced.getMaterialId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "录音请求标识已用于另一条知识");
            }
            return toView(raced);
        }
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
                            ));
        } catch (RuntimeException error) {
            transactionTemplate.executeWithoutResult(status -> attemptMapper.update(null,
                    new LambdaUpdateWrapper<RecitationAttempt>()
                            .eq(RecitationAttempt::getId, attemptId)
                            .eq(RecitationAttempt::getStatus, RecitationAttemptStatus.UPLOADING)
                            .set(RecitationAttempt::getStatus, RecitationAttemptStatus.FAILED)
                            .set(RecitationAttempt::getErrorMessage, "录音上传失败，请稍后重试")
                            ));
            throw error;
        }
        return getAttempt(userId, attemptId);
    }

    private String normalizeClientRequestId(String value) {
        if (value != null && !StringUtils.hasText(value)) {
            throw new IllegalArgumentException("录音请求标识不能为空");
        }
        String normalized = StringUtils.hasText(value) ? value.trim() : UUID.randomUUID().toString();
        if (normalized.length() > 64) throw new IllegalArgumentException("录音请求标识不能超过64个字符");
        return normalized;
    }

    private RecitationAttempt findAttemptByRequestId(Long userId, String requestId) {
        return attemptMapper.selectOne(new LambdaQueryWrapper<RecitationAttempt>()
                .eq(RecitationAttempt::getUserId, userId)
                .eq(RecitationAttempt::getClientRequestId, requestId));
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
                material.getLastReviewAt(), material.getNextReviewAt(), material.getArchivedAt(),
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
