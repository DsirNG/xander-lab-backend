package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.dto.agent.BlogAgentTaskCreateRequest;
import com.xander.lab.dto.agent.BlogAgentTaskVO;
import com.xander.lab.dto.agent.BlogAgentSessionVO;
import com.xander.lab.dto.BlogPostDTO;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.entity.BlogAgentSource;
import com.xander.lab.entity.BlogAgentTask;
import com.xander.lab.entity.BlogAgentVersion;
import com.xander.lab.entity.BlogMediaAsset;
import com.xander.lab.entity.BlogAgentMessage;
import com.xander.lab.config.BlogAgentProperties;
import com.xander.lab.mapper.BlogAgentSourceMapper;
import com.xander.lab.mapper.BlogAgentTaskMapper;
import com.xander.lab.mapper.BlogAgentVersionMapper;
import com.xander.lab.mapper.BlogAgentMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

@Service
@RequiredArgsConstructor
public class BlogAgentService {
    private final BlogAgentTaskMapper taskMapper;
    private final BlogAgentSourceMapper sourceMapper;
    private final BlogAgentVersionMapper versionMapper;
    private final BlogAgentModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final BlogService blogService;
    private final BlogAgentImageClient imageClient;
    private final BlogMediaService mediaService;
    private final BlogAgentProperties properties;
    private final BlogAgentMessageMapper messageMapper;

    @Transactional
    public BlogAgentTask create(Long userId, BlogAgentTaskCreateRequest request) {
        BlogAgentTask task = new BlogAgentTask();
        task.setUserId(userId);
        task.setInput(request.getInput().trim());
        task.setInputType("pending");
        task.setAudience("");
        task.setTone("");
        task.setStatus("created");
        task.setStage("analyze");
        task.setTagsJson("[]");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        saveMessage(task.getId(), "user", "message", null, task.getInput());
        return task;
    }

    /** Executes analyze → research → write → review as one durable task run. */
    public BlogAgentTaskVO run(Long taskId, Long userId) {
        return execute(taskId, userId, false, ignored -> { });
    }

    public BlogAgentTaskVO runStream(Long taskId, Long userId, Consumer<String> onDelta) {
        return runStream(taskId, userId, (event, data) -> {
            if ("delta".equals(event)) onDelta.accept(data);
        });
    }

    public BlogAgentTaskVO runStream(Long taskId, Long userId, BiConsumer<String, String> onEvent) {
        return execute(taskId, userId, true, onEvent);
    }

    private BlogAgentTaskVO execute(Long taskId, Long userId, boolean streaming, Consumer<String> onDelta) {
        return execute(taskId, userId, streaming, (event, data) -> {
            if ("delta".equals(event)) onDelta.accept(data);
        });
    }

    private BlogAgentTaskVO execute(Long taskId, Long userId, boolean streaming, BiConsumer<String, String> onEvent) {
        transactionTemplate.executeWithoutResult(status -> markRunning(taskId, userId));

        try {
            updateStage(taskId, userId, "analyze", "正在理解你的目标和文章边界", onEvent);
            JsonNode analysis = modelClient.analyze(getInput(taskId, userId));
            updateStage(taskId, userId, "research", "正在联网查找并核验相关资料", onEvent);
            JsonNode research = modelClient.research(getInput(taskId, userId), analysis);
            updateStage(taskId, userId, "write", "正在根据策划和调研结果撰写文章草稿", onEvent);
            String writingInput = getInput(taskId, userId)
                    + "\n\n策划结果：\n" + analysis
                    + "\n\n调研结果：\n" + research;
            JsonNode result = streaming
                    ? modelClient.createArticleStream(writingInput, delta -> onEvent.accept("delta", delta))
                    : modelClient.createArticle(writingInput);
            transactionTemplate.executeWithoutResult(status -> persistArticleDraft(taskId, userId, result));
            updateStage(taskId, userId, "illustrate", "正在按需生成并保存知识插图", onEvent);
            IllustrationOutcome outcome = generateIllustrations(taskId, userId, result.path("illustrations"));
            updateStage(taskId, userId, "review", "正在进行最终逻辑与表达审校", onEvent);
            IllustrationOutcome reviewed = reviewOutcome(outcome, getInput(taskId, userId));
            return transactionTemplate.execute(status -> finalizeResult(taskId, userId, reviewed));
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> markFailed(taskId, userId, e.getMessage()));
            throw e;
        }
    }

    private void updateStage(Long taskId, Long userId, String stage, String message,
                             BiConsumer<String, String> onEvent) {
        transactionTemplate.executeWithoutResult(status -> {
            BlogAgentTask task = requireOwnedTask(taskId, userId);
            task.setStatus("running");
            task.setStage(stage);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            saveMessage(taskId, "assistant", "process", stage, message);
        });
        onEvent.accept("stage", stage + "|" + message);
    }

    private IllustrationOutcome reviewOutcome(IllustrationOutcome outcome, String request) {
        try {
            JsonNode reviewed = modelClient.reviewArticle(outcome.content(), request);
            String content = defaultText(reviewed.path("content").asText(), outcome.content());
            return new IllustrationOutcome(content, outcome.status(), outcome.error(), outcome.generated());
        } catch (Exception ignored) {
            return outcome;
        }
    }

    private void markRunning(Long taskId, Long userId) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        task.setStatus("running");
        task.setStage("research");
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private String getInput(Long taskId, Long userId) {
        return requireOwnedTask(taskId, userId).getInput();
    }

    private void persistArticleDraft(Long taskId, Long userId, JsonNode result) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        applyResult(task, result);
        saveSources(task.getId(), result.path("sources"));
        task.setStatus("running");
        task.setStage("illustrate");
        task.setIllustrationStatus(imageClient.isEnabled() ? "running" : "disabled");
        task.setIllustrationError(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private BlogAgentTaskVO finalizeResult(Long taskId, Long userId, IllustrationOutcome outcome) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        task.setContent(outcome.content());
        task.setIllustrationStatus(outcome.status());
        task.setIllustrationError(limit(outcome.error(), 1000));
        saveVersion(task, outcome.generated() > 0
                ? "智能体完成调研、写作、插图与审校"
                : "智能体完成调研、写作与审校");
        task.setStatus("ready");
        task.setStage("review");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        saveMessage(taskId, "assistant", "result", "review", task.getTitle());
        return get(taskId, userId);
    }

    private void markFailed(Long taskId, Long userId, String errorMessage) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        task.setStatus("failed");
        task.setStage("analyze");
        task.setErrorMessage(limit(errorMessage, 1000));
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    public BlogAgentTaskVO get(Long taskId, Long userId) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        BlogAgentTaskVO vo = new BlogAgentTaskVO();
        vo.setTask(task);
        vo.setTags(readTags(task.getTagsJson()));
        vo.setContentBoundary(readObject(task.getContentBoundary()));
        vo.setKnowledgeGraph(readObject(task.getKnowledgeGraphJson()));
        vo.setSources(sourceMapper.selectList(new LambdaQueryWrapper<BlogAgentSource>()
                .eq(BlogAgentSource::getTaskId, taskId).orderByAsc(BlogAgentSource::getId)));
        vo.setVersions(versionMapper.selectList(new LambdaQueryWrapper<BlogAgentVersion>()
                .eq(BlogAgentVersion::getTaskId, taskId).orderByDesc(BlogAgentVersion::getVersionNo)));
        vo.setIllustrations(mediaService.getTaskImages(userId, taskId));
        vo.setMessages(messageMapper.selectList(new LambdaQueryWrapper<BlogAgentMessage>()
                .eq(BlogAgentMessage::getTaskId, taskId).orderByAsc(BlogAgentMessage::getId)));
        return vo;
    }

    public List<BlogAgentSessionVO> listSessions(Long userId) {
        if (userId == null) throw new IllegalArgumentException("未登录或登录已过期");
        return taskMapper.selectList(new LambdaQueryWrapper<BlogAgentTask>()
                        .eq(BlogAgentTask::getUserId, userId)
                        .orderByDesc(BlogAgentTask::getUpdatedAt)
                        .last("LIMIT 50"))
                .stream()
                .map(task -> BlogAgentSessionVO.builder()
                        .id(task.getId())
                        .title(defaultText(task.getTitle(), excerpt(task.getInput(), 36)))
                        .input(task.getInput())
                        .status(task.getStatus())
                        .stage(task.getStage())
                        .createdAt(task.getCreatedAt())
                        .updatedAt(task.getUpdatedAt())
                        .build())
                .toList();
    }

    public BlogAgentTaskVO reviseStream(Long taskId, Long userId, String instruction,
                                        BiConsumer<String, String> onEvent) {
        BlogAgentTask existing = requireOwnedTask(taskId, userId);
        if (!"ready".equals(existing.getStatus())) throw new IllegalStateException("当前文章尚未准备好，不能修改");
        transactionTemplate.executeWithoutResult(status -> {
            saveMessage(taskId, "user", "message", null, instruction.trim());
            existing.setStatus("running");
            existing.setStage("write");
            existing.setErrorMessage(null);
            existing.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(existing);
        });
        try {
            updateStage(taskId, userId, "write", "正在根据你的新要求修改文章", onEvent);
            JsonNode result = modelClient.reviseArticleStream(
                    requireOwnedTask(taskId, userId).getContent(), instruction,
                    delta -> onEvent.accept("delta", delta));
            transactionTemplate.executeWithoutResult(status -> persistArticleDraft(taskId, userId, result));
            updateStage(taskId, userId, "illustrate", "正在同步调整文章插图", onEvent);
            IllustrationOutcome outcome = generateIllustrations(taskId, userId, result.path("illustrations"));
            updateStage(taskId, userId, "review", "正在检查本轮修改", onEvent);
            IllustrationOutcome reviewed = reviewOutcome(outcome, instruction);
            return transactionTemplate.execute(status -> finalizeResult(taskId, userId, reviewed));
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> markRevisionFailed(taskId, userId, e.getMessage()));
            throw e;
        }
    }

    private void markRevisionFailed(Long taskId, Long userId, String errorMessage) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        task.setStatus("ready");
        task.setStage("review");
        task.setErrorMessage(limit(errorMessage, 1000));
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        saveMessage(taskId, "assistant", "process", "review", "本轮修改失败，已保留上一版文章");
    }

    private void saveMessage(Long taskId, String role, String kind, String stage, String content) {
        BlogAgentMessage message = new BlogAgentMessage();
        message.setTaskId(taskId);
        message.setRole(role);
        message.setKind(kind);
        message.setStage(stage);
        message.setContent(defaultText(content, ""));
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    private IllustrationOutcome generateIllustrations(Long taskId, Long userId, JsonNode plans) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        String content = task.getContent();
        if (!plans.isArray() || plans.isEmpty()) {
            return new IllustrationOutcome(removeIllustrationPlaceholders(content), "none", "", 0);
        }
        if (!imageClient.isEnabled()) {
            return new IllustrationOutcome(removeIllustrationPlaceholders(content), "disabled",
                    "未配置 BLOG_AGENT_IMAGE_MODEL，已跳过插图生成", 0);
        }

        int generatedCount = 0;
        List<String> errors = new ArrayList<>();
        int limit = Math.max(0, Math.min(properties.getMaxIllustrations(), 3));
        for (int index = 0; index < Math.min(plans.size(), limit); index++) {
            JsonNode plan = plans.get(index);
            String placeholder = normalizePlaceholder(plan.path("placeholder").asText(), index);
            String title = defaultText(plan.path("title").asText(), "知识插图 " + (index + 1));
            String alt = sanitizeAlt(defaultText(plan.path("alt").asText(), title));
            String prompt = plan.path("prompt").asText();
            if (!StringUtils.hasText(prompt)) {
                content = content.replace(placeholder, "");
                continue;
            }
            try {
                BlogAgentImageClient.GeneratedImage generated = imageClient.generate(
                        "为中文知识博客生成一张准确、克制、专业的知识插图。画面必须服务于理解，不要添加水印。"
                                + "如果包含文字，确保文字简短清晰。插图要求：" + prompt);
                String fileName = "agent-" + taskId + "-" + (index + 1) + "." + generated.extension();
                String meta = objectMapper.writeValueAsString(java.util.Map.of(
                        "title", title,
                        "alt", alt,
                        "prompt", prompt,
                        "model", properties.getImageModel(),
                        "size", properties.getImageSize()));
                BlogMediaAsset asset = mediaService.saveAgentImage(userId, taskId, fileName, generated, meta);
                String markdown = "![" + alt + "](" + asset.getUrl() + ")";
                content = replaceOrAppend(content, placeholder, markdown);
                generatedCount++;
            } catch (Exception e) {
                errors.add(title + "：" + defaultText(e.getMessage(), "生成失败"));
                content = content.replace(placeholder, "");
            }
        }
        content = removeIllustrationPlaceholders(content);
        String status = errors.isEmpty() ? "complete" : generatedCount > 0 ? "partial" : "failed";
        return new IllustrationOutcome(content, status, String.join("；", errors), generatedCount);
    }

    private String normalizePlaceholder(String value, int index) {
        if (StringUtils.hasText(value) && value.matches("<!-- illustration:[a-z0-9-]+ -->")) return value;
        return "<!-- illustration:auto-" + (index + 1) + " -->";
    }

    private String replaceOrAppend(String content, String placeholder, String markdown) {
        if (content.contains(placeholder)) return content.replace(placeholder, markdown);
        int references = content.indexOf("\n## 参考资料");
        if (references >= 0) return content.substring(0, references) + "\n\n" + markdown + "\n" + content.substring(references);
        return content + "\n\n" + markdown + "\n";
    }

    private String removeIllustrationPlaceholders(String content) {
        return content.replaceAll("(?m)^\\s*<!-- illustration:[a-z0-9-]+ -->\\s*$", "").trim();
    }

    private String sanitizeAlt(String value) {
        return limit(value.replace("[", "").replace("]", "").replace("\n", " "), 180);
    }

    @Transactional
    public BlogPostVO publish(Long taskId, Long userId) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        if (!"ready".equals(task.getStatus())) throw new IllegalStateException("文章尚未生成完成");
        if (task.getPublishedPostId() != null) return blogService.getBlogById(task.getPublishedPostId());
        BlogPostDTO post = new BlogPostDTO();
        post.setTitle(task.getTitle());
        post.setSummary(task.getSummary());
        post.setContent(task.getContent());
        post.setCategoryId(task.getCategoryId());
        post.setTags(readTags(task.getTagsJson()));
        BlogPostVO published = blogService.createBlog(post, true, "agent-task-" + taskId);
        task.setPublishedPostId(published.getId());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return published;
    }

    private BlogAgentTask requireOwnedTask(Long taskId, Long userId) {
        BlogAgentTask task = taskMapper.selectById(taskId);
        if (task == null || userId == null || !userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("智能体任务不存在");
        }
        return task;
    }

    private void applyResult(BlogAgentTask task, JsonNode result) {
        String title = required(result, "title");
        String content = required(result, "content");
        task.setTitle(limit(title, 255));
        task.setSummary(defaultText(result.path("summary").asText(), excerpt(content, 160)));
        task.setContent(content);
        task.setOutline(result.path("outline").asText(""));
        JsonNode writingBrief = result.path("writingBrief");
        task.setInputType(normalizeInputType(writingBrief.path("inputNature").asText()));
        task.setAudience(limit(defaultText(writingBrief.path("audience").asText(), "广泛读者"), 120));
        task.setTone(limit(defaultText(writingBrief.path("tone").asText(), "清晰、准确、可读"), 60));
        task.setContentBoundary(writeNode(result.path("contentBoundary")));
        task.setKnowledgeGraphJson(writeNode(result.path("knowledgeGraph")));
        task.setCategoryId(normalizeCategory(result.path("categoryId").asText()));
        task.setReview(result.path("review").asText(""));
        try {
            task.setTagsJson(objectMapper.writeValueAsString(readStringArray(result.path("tags"))));
        } catch (Exception e) {
            task.setTagsJson("[]");
        }
    }

    private void saveSources(Long taskId, JsonNode sourceNodes) {
        sourceMapper.delete(new LambdaQueryWrapper<BlogAgentSource>().eq(BlogAgentSource::getTaskId, taskId));
        if (!sourceNodes.isArray()) return;
        for (JsonNode node : sourceNodes) {
            String url = node.path("url").asText();
            if (!StringUtils.hasText(url) || !url.startsWith("http") || url.length() > 2000) continue;
            BlogAgentSource source = new BlogAgentSource();
            source.setTaskId(taskId);
            source.setTitle(limit(defaultText(node.path("title").asText(), url), 500));
            source.setUrl(url);
            source.setPublisher(limit(node.path("publisher").asText(""), 255));
            source.setExcerpt(node.path("excerpt").asText(""));
            source.setReliability(limit(defaultText(node.path("reliability").asText(), "未标注"), 64));
            source.setRetrievedAt(LocalDateTime.now());
            sourceMapper.insert(source);
        }
    }

    private void saveVersion(BlogAgentTask task, String note) {
        Long count = versionMapper.selectCount(new LambdaQueryWrapper<BlogAgentVersion>().eq(BlogAgentVersion::getTaskId, task.getId()));
        BlogAgentVersion version = new BlogAgentVersion();
        version.setTaskId(task.getId());
        version.setVersionNo(Math.toIntExact(count + 1));
        version.setTitle(task.getTitle());
        version.setSummary(task.getSummary());
        version.setContent(task.getContent());
        version.setChangeNote(note);
        version.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(version);
    }

    private List<String> readTags(String json) {
        try { return objectMapper.readValue(defaultText(json, "[]"), new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private java.util.Map<String, Object> readObject(String json) {
        try { return objectMapper.readValue(defaultText(json, "{}"), new TypeReference<java.util.Map<String, Object>>() {}); }
        catch (Exception e) { return java.util.Map.of(); }
    }

    private String writeNode(JsonNode node) {
        try { return node != null && node.isObject() ? objectMapper.writeValueAsString(node) : "{}"; }
        catch (Exception e) { return "{}"; }
    }

    private List<String> readStringArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (!node.isArray()) return result;
        node.forEach(value -> { if (StringUtils.hasText(value.asText())) result.add(value.asText().trim()); });
        return result.stream().distinct().limit(8).toList();
    }

    private String normalizeCategory(String category) {
        return List.of("frontend", "backend", "architecture", "devops", "career").contains(category) ? category : "career";
    }

    private String normalizeInputType(String inputType) {
        return List.of("topic", "project_context", "journal").contains(inputType) ? inputType : "topic";
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!StringUtils.hasText(value)) throw new IllegalStateException("模型未返回 " + field);
        return value;
    }

    private String defaultText(String value, String fallback) { return StringUtils.hasText(value) ? value.trim() : fallback; }
    private String limit(String value, int maxLength) {
        String safeValue = value == null ? "" : value;
        return safeValue.length() <= maxLength ? safeValue : safeValue.substring(0, maxLength);
    }
    private String excerpt(String content, int length) { return content.length() <= length ? content : content.substring(0, length - 1) + "…"; }

    private record IllustrationOutcome(String content, String status, String error, int generated) {}
}
