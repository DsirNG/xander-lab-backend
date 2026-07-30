package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.dto.img2three.Img2ThreeTaskVO;
import com.xander.lab.dto.img2three.Img2ThreeTaskSummaryVO;
import com.xander.lab.entity.BlogMediaAsset;
import com.xander.lab.entity.Img2ThreeTask;
import com.xander.lab.mapper.Img2ThreeTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;
import java.util.List;

@Service
@RequiredArgsConstructor
public class Img2ThreeService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Img2ThreeTaskMapper taskMapper;
    private final BlogMediaService mediaService;
    private final Img2ThreeModelClient modelClient;
    private final Img2ThreePipeline pipeline;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public Img2ThreeTask create(Long userId, MultipartFile file) {
        if (userId == null) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        BlogMediaAsset asset = mediaService.uploadImage(userId, file);
        Img2ThreeTask task = new Img2ThreeTask();
        task.setUserId(userId);
        task.setStatus("created");
        task.setStage("analyze");
        task.setReferenceMediaUrl(asset.getUrl());
        task.setReferenceMediaAssetId(asset.getId());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
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

    public Img2ThreeTaskVO runStream(Long taskId, Long userId, BiConsumer<String, String> onEvent) {
        Boolean claimed = transactionTemplate.execute(status -> claimRunning(taskId, userId));
        if (!Boolean.TRUE.equals(claimed)) {
            throw new IllegalStateException("任务正在运行，请等待当前重建完成");
        }
        try {
            emit(onEvent, "stage", "analyze|正在观察参考图与主体结构");
            updateStage(taskId, userId, "analyze");

            Img2ThreeTask task = requireOwned(taskId, userId);
            byte[] imageBytes = downloadBytes(task.getReferenceMediaUrl());
            String contentType = guessContentType(task.getReferenceMediaUrl());

            emit(onEvent, "stage", "spec|正在生成受限场景规格");
            updateStage(taskId, userId, "spec");

            String starterSpecJson = pipeline.createStarterSpec();
            JsonNode result = modelClient.reconstruct(imageBytes, contentType, starterSpecJson);
            JsonNode analysis = result.path("analysis");
            JsonNode sceneSpec = result.path("spec");
            String title = result.path("title").asText(null);
            if (!StringUtils.hasText(title) && sceneSpec.hasNonNull("title")) {
                title = sceneSpec.path("title").asText("");
            }
            if (sceneSpec.isMissingNode() || sceneSpec.isNull() || !sceneSpec.isObject()) {
                throw new IllegalStateException("模型未返回 img2threejs ObjectSculptSpec");
            }

            emit(onEvent, "stage", "factory|正在整理可下载的 TypeScript 工厂");
            updateStage(taskId, userId, "factory");

            String analysisJson = objectMapper.writeValueAsString(analysis.isMissingNode() ? objectMapper.createObjectNode() : analysis);
            String specJson = objectMapper.writeValueAsString(sceneSpec);
            String finalTitle = StringUtils.hasText(title) ? title : "Generated Model";
            String finalFactory = pipeline.validateAndGenerate(finalTitle, specJson);
            transactionTemplate.executeWithoutResult(status -> {
                LambdaUpdateWrapper<Img2ThreeTask> update = new LambdaUpdateWrapper<Img2ThreeTask>()
                        .eq(Img2ThreeTask::getId, taskId)
                        .eq(Img2ThreeTask::getUserId, userId)
                        .set(Img2ThreeTask::getStatus, "ready")
                        .set(Img2ThreeTask::getStage, "factory")
                        .set(Img2ThreeTask::getTitle, finalTitle)
                        .set(Img2ThreeTask::getAnalysisJson, analysisJson)
                        .set(Img2ThreeTask::getSpecJson, specJson)
                        .set(Img2ThreeTask::getFactoryCode, finalFactory)
                        .set(Img2ThreeTask::getErrorMessage, null)
                        .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now());
                taskMapper.update(null, update);
            });
            return get(taskId, userId);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "重建失败" : e.getMessage();
            transactionTemplate.executeWithoutResult(status -> markFailed(taskId, userId, message));
            throw e instanceof IllegalStateException || e instanceof IllegalArgumentException
                    ? (RuntimeException) e
                    : new IllegalStateException(message, e);
        }
    }

    private boolean claimRunning(Long taskId, Long userId) {
        requireOwned(taskId, userId);
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, taskId)
                .eq(Img2ThreeTask::getUserId, userId)
                .ne(Img2ThreeTask::getStatus, "running")
                .set(Img2ThreeTask::getStatus, "running")
                .set(Img2ThreeTask::getErrorMessage, null)
                .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now()));
        return updated == 1;
    }

    private void markFailed(Long taskId, Long userId, String message) {
        taskMapper.update(null, new LambdaUpdateWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, taskId)
                .eq(Img2ThreeTask::getUserId, userId)
                .set(Img2ThreeTask::getStatus, "failed")
                .set(Img2ThreeTask::getErrorMessage, truncate(message, 1000))
                .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now()));
    }

    private void updateStage(Long taskId, Long userId, String stage) {
        taskMapper.update(null, new LambdaUpdateWrapper<Img2ThreeTask>()
                .eq(Img2ThreeTask::getId, taskId)
                .eq(Img2ThreeTask::getUserId, userId)
                .set(Img2ThreeTask::getStage, stage)
                .set(Img2ThreeTask::getUpdatedAt, LocalDateTime.now()));
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
        } catch (Exception ignored) {
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
        try (InputStream in = URI.create(url).toURL().openStream()) {
            return in.readAllBytes();
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

    private String fallbackFactoryCode(String title) {
        String safe = StringUtils.hasText(title) ? title.replace("\"", "'") : "Generated Model";
        return """
                import * as THREE from 'three';

                /** Generated for: %s */
                export function createModel(): THREE.Group {
                  const root = new THREE.Group();
                  root.name = 'generated-model';
                  const mesh = new THREE.Mesh(
                    new THREE.BoxGeometry(1, 1, 1),
                    new THREE.MeshStandardMaterial({ color: 0x888888, metalness: 0.2, roughness: 0.6 })
                  );
                  root.add(mesh);
                  return root;
                }
                """.formatted(safe);
    }

    private void emit(BiConsumer<String, String> onEvent, String event, String data) {
        if (onEvent != null) onEvent.accept(event, data);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
