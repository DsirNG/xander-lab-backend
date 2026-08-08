package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.dto.img2three.Img2ThreeTaskVO;
import com.xander.lab.dto.img2three.Img2ThreeTaskSummaryVO;
import com.xander.lab.entity.BlogMediaAsset;
import com.xander.lab.entity.Img2ThreeTask;
import com.xander.lab.config.Img2ThreeProperties;
import com.xander.lab.mapper.Img2ThreeTaskMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.List;

@Service
@RequiredArgsConstructor
public class Img2ThreeService {
    private static final Logger log = LoggerFactory.getLogger(Img2ThreeService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int MAX_SPEC_REPAIR_ATTEMPTS = 2;

    private final Img2ThreeTaskMapper taskMapper;
    private final BlogMediaService mediaService;
    private final Img2ThreeModelClient modelClient;
    private final Img2ThreePipeline pipeline;
    private final Img2ThreeReliefGenerator reliefGenerator;
    private final Img2ThreeProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public Img2ThreeTaskVO create(Long userId, MultipartFile file) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        // OSS is a remote network call. Keep it outside a database transaction.
        BlogMediaAsset asset = mediaService.uploadImage(userId, file);
        Img2ThreeTask task = new Img2ThreeTask();
        task.setUserId(userId);
        task.setStatus("created");
        task.setStage("analyze");
        task.setTitle(titleFromFileName(file.getOriginalFilename()));
        task.setReferenceMediaUrl(asset.getUrl());
        task.setReferenceMediaAssetId(asset.getId());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        transactionTemplate.executeWithoutResult(status -> taskMapper.insert(task));
        return toVo(task);
    }

    public Img2ThreeTaskVO get(Long taskId, Long userId) {
        return toVo(requireOwned(taskId, userId));
    }

    public List<Img2ThreeTaskSummaryVO> list(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        return taskMapper.selectList(new LambdaQueryWrapper<Img2ThreeTask>()
                        .eq(Img2ThreeTask::getUserId, userId)
                        .orderByDesc(Img2ThreeTask::getCreatedAt)
                        .last("LIMIT 50"))
                .stream()
                .map(this::toSummaryVo)
                .toList();
    }

    /** A process restart cannot resume an in-memory worker, so stale records must not poll forever. */
    @Scheduled(fixedDelay = 60_000)
    public void failAbandonedRunningTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(20);
        List<Img2ThreeTask> abandonedTasks = taskMapper.selectList(new LambdaQueryWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getStatus, "running")
                .lt(Img2ThreeTask::getUpdatedAt, cutoff)
                .last("LIMIT 100"));
        for (Img2ThreeTask task : abandonedTasks) {
            failTimedOutExecution(task, cutoff);
        }
    }

    public Img2ThreeTaskVO runStream(Long taskId, Long userId, BiConsumer<String, String> onEvent) {
        Img2ThreeTask existing = requireOwned(taskId, userId);
        if ("ready".equals(existing.getStatus()) && StringUtils.hasText(existing.getSpecJson())) {
            return toVo(existing);
        }
        String runToken = transactionTemplate.execute(status -> claimRunning(taskId, userId));
        if (!StringUtils.hasText(runToken)) {
            throw new IllegalStateException("任务正在运行，请等待当前重建完成");
        }
        try {
            emit(onEvent, "stage", "analyze|正在观察参考图与主体结构");
            requireActiveExecution(updateStage(taskId, userId, runToken, "analyze"));

            Img2ThreeTask task = requireOwned(taskId, userId);
            byte[] imageBytes = downloadBytes(task.getReferenceMediaUrl());
            String contentType = guessContentType(task.getReferenceMediaUrl());

            // Always prepare a local, deterministic model first. This guarantees that a task
            // can finish even when the optional vision model or strict upstream gate is offline.
            Img2ThreeReliefGenerator.GeneratedRelief relief = reliefGenerator.generate(
                    imageBytes,
                    contentType,
                    task.getReferenceMediaUrl(),
                    task.getTitle()
            );

            emit(onEvent, "stage", "spec|正在提取主体轮廓、纹理与浮雕深度");
            requireActiveExecution(updateStage(taskId, userId, runToken, "spec"));

            GeneratedModel generated = new GeneratedModel(
                    relief.title(), relief.analysis(), relief.sceneSpec(), relief.factoryCode()
            );
            if (properties.isAgentEnabled()) {
                try {
                    generated = generateAdvancedModel(taskId, imageBytes, contentType, onEvent);
                } catch (Exception advancedError) {
                    String reason = advancedError.getMessage() == null
                            ? advancedError.getClass().getSimpleName()
                            : advancedError.getMessage();
                    log.warn("img2three task {} advanced reconstruction failed; using deterministic relief: {}",
                            taskId, reason, advancedError);
                    relief.analysis().put("advancedFallbackReason", truncate(reason, 500));
                    emit(onEvent, "stage", "spec|智能重建暂不可用，正在完成可靠的图片浮雕模型");
                }
            }

            emit(onEvent, "stage", "factory|正在整理可下载的 TypeScript 工厂");
            requireActiveExecution(updateStage(taskId, userId, runToken, "factory"));

            String analysisJson = objectMapper.writeValueAsString(generated.analysis());
            String finalSpecJson = objectMapper.writeValueAsString(generated.sceneSpec());
            String finalTitle = generated.title();
            String generatedFactory = generated.factoryCode();
            Integer completed = transactionTemplate.execute(status -> completeRunning(
                    taskId,
                    userId,
                    runToken,
                    finalTitle,
                    analysisJson,
                    finalSpecJson,
                    generatedFactory
            ));
            if (!Integer.valueOf(1).equals(completed)) {
                throw new IllegalStateException("任务状态已变化，本次重建结果未覆盖较新的任务状态");
            }
            return get(taskId, userId);
        } catch (Exception e) {
            log.warn("img2three task {} failed at its current stage", taskId, e);
            String message = e.getMessage() == null ? "重建失败" : e.getMessage();
            transactionTemplate.executeWithoutResult(status -> markFailed(taskId, userId, runToken, message));
            throw e instanceof IllegalStateException || e instanceof IllegalArgumentException
                    ? (RuntimeException) e
                    : new IllegalStateException(message, e);
        }
    }

    private GeneratedModel generateAdvancedModel(Long taskId,
                                                 byte[] imageBytes,
                                                 String contentType,
                                                 BiConsumer<String, String> onEvent) throws Exception {
        if (!pipeline.isAvailable()) {
            throw new IllegalStateException("img2threejs pipeline is unavailable");
        }
        log.info("img2three task {} creating starter spec", taskId);
        String starterSpecJson = pipeline.createStarterSpec();
        log.info("img2three task {} requesting model reconstruction (imageBytes={}, starterSpecChars={})",
                taskId, imageBytes.length, starterSpecJson.length());
        JsonNode result = modelClient.reconstruct(imageBytes, contentType, starterSpecJson);
        log.info("img2three task {} received initial model response", taskId);
        JsonNode analysis = objectMapper.createObjectNode();
        JsonNode sceneSpec = null;
        String title = null;
        String factoryCode = null;

        for (int attempt = 0; attempt <= MAX_SPEC_REPAIR_ATTEMPTS; attempt++) {
            analysis = result.path("analysis");
            sceneSpec = extractObjectSculptSpec(result);
            title = extractTitle(result, sceneSpec);

            if (sceneSpec == null) {
                if (attempt == MAX_SPEC_REPAIR_ATTEMPTS) {
                    throw new IllegalStateException("模型未返回 img2threejs ObjectSculptSpec");
                }
                emit(onEvent, "stage", "spec|模型返回结构不完整，正在按 img2threejs 契约修订");
                result = modelClient.repair(
                        imageBytes,
                        contentType,
                        result,
                        "The response did not contain an ObjectSculptSpec. Return either a spec object with "
                                + "schemaVersion/componentTree/materials, or preferably wrap it as "
                                + "{\"title\": string, \"analysis\": object, \"spec\": ObjectSculptSpec}."
                );
                continue;
            }

            String specJson = objectMapper.writeValueAsString(sceneSpec);
            String candidateTitle = StringUtils.hasText(title) ? title : "Generated Model";
            try {
                factoryCode = pipeline.validateAndGenerate(candidateTitle, specJson);
                break;
            } catch (IllegalStateException e) {
                if (!isQualityGateFailure(e) || attempt == MAX_SPEC_REPAIR_ATTEMPTS) {
                    throw e;
                }
                emit(onEvent, "stage", "spec|质量门未通过，正在根据完整报告修订规格");
                result = modelClient.repair(imageBytes, contentType, result, e.getMessage());
            }
        }

        JsonNode safeAnalysis = analysis == null || analysis.isMissingNode()
                ? objectMapper.createObjectNode()
                : analysis;
        return new GeneratedModel(
                StringUtils.hasText(title) ? title : "Generated Model",
                safeAnalysis,
                sceneSpec,
                factoryCode
        );
    }

    private JsonNode extractObjectSculptSpec(JsonNode result) {
        if (result == null || !result.isObject()) return null;
        for (String field : List.of("spec", "objectSculptSpec", "sceneSpec")) {
            JsonNode wrapped = result.path(field);
            if (looksLikeObjectSculptSpec(wrapped)) return wrapped;
        }
        return looksLikeObjectSculptSpec(result) ? result : null;
    }

    private boolean looksLikeObjectSculptSpec(JsonNode candidate) {
        return candidate != null
                && candidate.isObject()
                && candidate.has("componentTree")
                && candidate.has("materials")
                && (candidate.has("schemaVersion") || candidate.has("qualityContract"));
    }

    private String extractTitle(JsonNode result, JsonNode sceneSpec) {
        String title = result == null ? null : result.path("title").asText(null);
        if (!StringUtils.hasText(title) && sceneSpec != null) {
            title = sceneSpec.path("title").asText(null);
        }
        if (!StringUtils.hasText(title) && sceneSpec != null) {
            title = sceneSpec.path("targetName").asText(null);
        }
        return title;
    }

    private boolean isQualityGateFailure(IllegalStateException error) {
        return error.getMessage() != null
                && error.getMessage().startsWith("img2threejs quality gate rejected");
    }

    String claimRunning(Long taskId, Long userId) {
        requireOwned(taskId, userId);
        String runToken = UUID.randomUUID().toString();
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, taskId)
                .eq(Img2ThreeTask::getUserId, userId)
                .in(Img2ThreeTask::getStatus, "created", "failed")
                .set(Img2ThreeTask::getStatus, "running")
                .set(Img2ThreeTask::getRunToken, runToken)
                .set(Img2ThreeTask::getErrorMessage, null)
                .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now()));
        return updated == 1 ? runToken : null;
    }

    int markFailed(Long taskId, Long userId, String runToken, String message) {
        return taskMapper.update(null, new LambdaUpdateWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, taskId)
                .eq(Img2ThreeTask::getUserId, userId)
                .eq(Img2ThreeTask::getStatus, "running")
                .eq(Img2ThreeTask::getRunToken, runToken)
                .set(Img2ThreeTask::getStatus, "failed")
                .set(Img2ThreeTask::getErrorMessage, truncate(message, 1000))
                .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now()));
    }

    int updateStage(Long taskId, Long userId, String runToken, String stage) {
        return taskMapper.update(null, new LambdaUpdateWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, taskId)
                .eq(Img2ThreeTask::getUserId, userId)
                .eq(Img2ThreeTask::getStatus, "running")
                .eq(Img2ThreeTask::getRunToken, runToken)
                .set(Img2ThreeTask::getStage, stage)
                .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now()));
    }

    int completeRunning(Long taskId,
                        Long userId,
                        String runToken,
                        String title,
                        String analysisJson,
                        String specJson,
                        String factoryCode) {
        return taskMapper.update(null, new LambdaUpdateWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, taskId)
                .eq(Img2ThreeTask::getUserId, userId)
                .eq(Img2ThreeTask::getStatus, "running")
                .eq(Img2ThreeTask::getRunToken, runToken)
                .set(Img2ThreeTask::getStatus, "ready")
                .set(Img2ThreeTask::getStage, "factory")
                .set(Img2ThreeTask::getTitle, title)
                .set(Img2ThreeTask::getAnalysisJson, analysisJson)
                .set(Img2ThreeTask::getSpecJson, specJson)
                .set(Img2ThreeTask::getFactoryCode, factoryCode)
                .set(Img2ThreeTask::getErrorMessage, null)
                .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now()));
    }

    private void failTimedOutExecution(Img2ThreeTask task, LocalDateTime cutoff) {
        LambdaUpdateWrapper<Img2ThreeTask> update = new LambdaUpdateWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, task.getId())
                .eq(Img2ThreeTask::getUserId, task.getUserId())
                .eq(Img2ThreeTask::getStatus, "running")
                .lt(Img2ThreeTask::getUpdatedAt, cutoff);
        if (StringUtils.hasText(task.getRunToken())) {
            update.eq(Img2ThreeTask::getRunToken, task.getRunToken());
        } else {
            // Legacy running rows created before the run-token migration still need one-time recovery.
            update.isNull(Img2ThreeTask::getRunToken);
        }
        update.set(Img2ThreeTask::getStatus, "failed")
                .set(Img2ThreeTask::getErrorMessage,
                        "Reconstruction worker timed out or was interrupted; run the task again")
                .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now());
        taskMapper.update(null, update);
    }

    private void requireActiveExecution(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("任务执行代次已变化，本次旧重建已停止");
        }
    }

    private Img2ThreeTask requireOwned(Long taskId, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        Img2ThreeTask task = taskMapper.selectOne(new LambdaQueryWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, taskId)
                .eq(Img2ThreeTask::getUserId, userId));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        return task;
    }

    private Img2ThreeTaskVO toVo(Img2ThreeTask task) {
        Img2ThreeTaskVO vo = new Img2ThreeTaskVO();
        vo.setId(task.getId());
        vo.setStatus(task.getStatus());
        vo.setStage(task.getStage());
        vo.setTitle(task.getTitle());
        vo.setReferenceMediaUrl(task.getReferenceMediaUrl());
        vo.setFactoryCode(task.getFactoryCode());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setCreatedAt(task.getCreatedAt() == null ? null : task.getCreatedAt().format(ISO));
        vo.setUpdatedAt(task.getUpdatedAt() == null ? null : task.getUpdatedAt().format(ISO));
        try {
            if (StringUtils.hasText(task.getAnalysisJson())) {
                vo.setAnalysis(objectMapper.readValue(task.getAnalysisJson(), Object.class));
            }
            if (StringUtils.hasText(task.getSpecJson())) {
                vo.setSceneSpec(objectMapper.readValue(task.getSpecJson(), Object.class));
            }
        } catch (Exception e) {
            log.warn("img2three task {} analysis/spec JSON unreadable, exposing raw task only", task.getId(), e);
            vo.setAnalysis(null);
            vo.setSceneSpec(null);
        }
        return vo;
    }

    private Img2ThreeTaskSummaryVO toSummaryVo(Img2ThreeTask task) {
        Img2ThreeTaskSummaryVO vo = new Img2ThreeTaskSummaryVO();
        vo.setId(task.getId());
        vo.setStatus(task.getStatus());
        vo.setStage(task.getStage());
        vo.setTitle(task.getTitle());
        vo.setReferenceMediaUrl(task.getReferenceMediaUrl());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setCreatedAt(task.getCreatedAt() == null ? null : task.getCreatedAt().format(ISO));
        vo.setUpdatedAt(task.getUpdatedAt() == null ? null : task.getUpdatedAt().format(ISO));
        return vo;
    }

    private byte[] downloadBytes(String url) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("参考图地址无效");
        }
        try {
            java.net.URLConnection connection = URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(30_000);
            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法读取参考图：" + e.getMessage(), e);
        }
    }

    private String guessContentType(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private String titleFromFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) return "Image relief model";
        String normalized = fileName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        int extension = name.lastIndexOf('.');
        if (extension > 0) name = name.substring(0, extension);
        if (!StringUtils.hasText(name)) return "Image relief model";
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    private void emit(BiConsumer<String, String> onEvent, String event, String data) {
        if (onEvent != null) onEvent.accept(event, data);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record GeneratedModel(
            String title,
            JsonNode analysis,
            JsonNode sceneSpec,
            String factoryCode
    ) {
    }
}
