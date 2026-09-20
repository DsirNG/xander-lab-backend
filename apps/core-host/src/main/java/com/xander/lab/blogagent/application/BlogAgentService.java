package com.xander.lab.blogagent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.api.BlogAgentTaskCreateRequest;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.blogagent.api.BlogAgentSessionVO;
import com.xander.lab.blogagent.spi.BlogAgentImage;
import com.xander.lab.blogagent.spi.BlogAgentImagePort;
import com.xander.lab.blogagent.spi.BlogAgentImageUsage;
import com.xander.lab.blogagent.spi.BlogAgentPort;
import com.xander.lab.blogagent.spi.BlogAgentPostView;
import com.xander.lab.blogagent.spi.BlogAgentTaskDraft;
import com.xander.lab.blogagent.spi.BlogAgentTaskView;
import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.payment.api.TokenUsage;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.blogagent.domain.BlogAgentSource;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.domain.BlogAgentVersion;
import com.xander.lab.blogagent.domain.BlogAgentMessage;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentLeaseScheduler;
import com.xander.lab.blogagent.infrastructure.BlogAgentModelClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentSourceMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentTaskMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentVersionMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentMessageMapper;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.publishing.application.PublicationService;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.content.api.PublishCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlogAgentService implements BlogAgentPort, BlogAgentImagePort {
    private static final long MAX_LEASE_RENEW_INTERVAL_SECONDS = 60L;

    private final BlogAgentTaskMapper taskMapper;
    private final BlogAgentSourceMapper sourceMapper;
    private final BlogAgentVersionMapper versionMapper;
    private final BlogAgentModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final BlogService blogService;
    private PublicationService publicationService;
    private final BlogAgentImageClient imageClient;
    private final MediaAssetPort mediaAssetPort;
    private final BlogAgentProperties properties;
    private final BlogAgentMessageMapper messageMapper;
    private final BlogAgentEventService eventService;
    private final BlogAgentLeaseScheduler leaseScheduler;
    private final BillingPort billingPort;

    @Autowired
    void setPublicationService(PublicationService publicationService) {
        this.publicationService = publicationService;
    }

    @Transactional
    public BlogAgentTask create(Long userId, BlogAgentTaskCreateRequest request) {
        String clientRequestId = normalizeClientRequestId(request.getClientRequestId());
        if (clientRequestId != null) {
            BlogAgentTask existing = findTaskByClientRequestId(userId, clientRequestId);
            if (existing != null) return existing;
        }
        BlogAgentTask task = new BlogAgentTask();
        task.setUserId(userId);
        task.setClientRequestId(clientRequestId);
        task.setInput(request.getInput().trim());
        task.setInputType("pending");
        task.setAudience("");
        task.setTone("");
        task.setStatus("created");
        task.setStage("analyze");
        task.setTagsJson("[]");
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            // A retry may race with the first durable insert. Return the winner instead of creating a second task.
            BlogAgentTask existing = findTaskByClientRequestId(userId, clientRequestId);
            if (existing != null) return existing;
            throw duplicate;
        }
        saveMessage(task.getId(), "user", "message", null, task.getInput());
        return task;
    }

    /**
     * {@inheritDoc}
     *
     * <p>薄壳 + 换算：把契约草稿装回域请求体，转调上面那个方法。幂等、去重、首次消息落库全都在
     * 那一份实现体里，只有一份 —— 复制一份到这里意味着以后改幂等规则要改两处，
     * 而且不会有任何编译错误提醒。</p>
     *
     * <p>⚠️ <b>这个 {@code @Transactional} 不是多余的。</b>Spring 的事务是代理实现的，而同类自调用
     * （{@code this.create(...)}）不走代理 —— 上面那个方法上的注解在这条路径上<b>不生效</b>。
     * 上面那个方法体里是"先按幂等键查、再插入"，靠事务边界把它与并发请求串起来；
     * 少了这一层，先查后插之间就会出现窗口，两个并发请求各建一个任务。
     * 症状是"偶尔重复"，比崩溃难查。由 {@code PortDelegationTransactionTest} 守着。</p>
     */
    @Override
    @Transactional
    public BlogAgentTaskView create(Long userId, BlogAgentTaskDraft draft) {
        return toTaskView(create(userId, toRequest(draft)));
    }

    /** Returns a completed task proven to belong to a stable create-request key. */
    @Transactional(readOnly = true)
    public BlogAgentTask findReadyTaskByClientRequestIdForView(Long userId, String clientRequestId) {
        String normalized = normalizeClientRequestId(clientRequestId);
        if (userId == null || normalized == null) return null;
        BlogAgentTask task = findTaskByClientRequestId(userId, normalized);
        return task != null && "ready".equals(task.getStatus()) ? task : null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>上面那个方法加了 {@code ForView} 后缀：它返回域实体 {@code BlogAgentTask}，而端口要返回
     * {@link BlogAgentTaskView}。Java 不允许同名同参只差返回值，所以端口拿规范名、
     * 同域富方法用后缀说明它的受众 —— 与 {@code EmailReminderService#updateStatusForView}、
     * {@code RecitationService#getMaterialForView} 同一个先例。</p>
     *
     * <p>这里没有参数换位（两个方法都是 {@code (userId, clientRequestId)}），
     * 但 {@code @Transactional(readOnly = true)} 仍要重写一遍 —— 自调用不走代理。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public BlogAgentTaskView findReadyTaskByClientRequestId(Long userId, String clientRequestId) {
        return toTaskView(findReadyTaskByClientRequestIdForView(userId, clientRequestId));
    }

    /** Generates a deduplicated topic series for a scheduled plan's topic queue.
     * Synchronous model call, no durable task is created; the caller owns
     * rate limiting and any persistence of the returned topics. */
    public List<String> generateTopicSeries(Long userId, String seed, String audience, String tone,
                                            int count, List<String> excludeTitles) {
        if (count < 1 || count > 30) {
            throw new IllegalArgumentException("主题数量需在 1-30 之间");
        }
        ModelCallResult call = modelClient.planTopicSeries(seed, audience, tone, count, excludeTitles);
        JsonNode response = call.payload();
        List<String> topics = new ArrayList<>();
        JsonNode topicNodes = response.path("topics");
        if (topicNodes.isArray()) {
            topicNodes.forEach(node -> {
                if (StringUtils.hasText(node.asText())) topics.add(node.asText().trim());
            });
        }
        List<String> result = topics.stream()
                .filter(title -> excludeTitles == null || excludeTitles.stream().noneMatch(excluded ->
                        excluded != null && title.equalsIgnoreCase(excluded.strip())))
                .distinct()
                .limit(count)
                .toList();
        if (!result.isEmpty()) {
            billingPort.chargeTokens(userId, BillingBiz.BLOG_TOPIC, "topics:" + userId + ":" + System.currentTimeMillis(),
                    "seed:" + seed, billable(call.usage()), call.model(), "AI 选题生成");
        }
        return result;
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
        // ⚠️ 必须点名 runStreamForView：四参的 runStream 现在是端口方法，参数是
        // (userId, taskId)。写回 runStream(taskId, userId, ...) 会静默地把两个 Long 换位。
        return runStreamForView(taskId, userId, onEvent, () -> false);
    }

    /**
     * Runs the pipeline with cooperative cancellation checks between remote stages and streamed deltas.
     *
     * <p>⚠️ {@code ForView} 后缀是必需的，不是风格问题：端口方法
     * {@code runStream(Long userId, Long taskId, BiConsumer, BooleanSupplier)} 与它**参数表逐字相同**
     * （都是 {@code (Long, Long, BiConsumer, BooleanSupplier)}），只有参数含义与返回值不同，
     * 两者不能同名共存，而端口拿规范名。同域调用方（三参重载、前端 SSE）继续走这个方法，
     * 拿完整的 {@code BlogAgentTaskVO}。</p>
     */
    public BlogAgentTaskVO runStreamForView(Long taskId, Long userId, BiConsumer<String, String> onEvent,
                                            BooleanSupplier cancellationRequested) {
        return execute(taskId, userId, true, onEvent, cancellationRequested);
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ <b>参数是换位的</b>：端口收 {@code (userId, taskId)}，而上面那个方法收
     * {@code (taskId, userId)}。这一行是最容易静默出错的地方 —— 写反了会把 userId 当 taskId
     * 去查，落到"任务不存在"（响亮的失败，不是静默的错数据），但仍然由
     * {@code BlogAgentPortMappingTest} 用互不相同的字面量（42 vs 9）钉住。</p>
     *
     * <p>这里<b>不加</b> {@code @Transactional}：流水线要跑几分钟、内含多次外部 HTTP 调用，
     * 本来就不该包在一个事务里。{@code PortDelegationTransactionTest} 的判据是"转调的兄弟方法
     * 带注解，才要求这一层也带" —— 这里兄弟方法没带，所以它不会误报。判据写成"看兄弟方法"
     * 而不是"一律要求"，正是为了不逼出一个语义错误的事务边界。</p>
     */
    @Override
    public BlogAgentTaskView runStream(Long userId, Long taskId, BiConsumer<String, String> onEvent,
                                       BooleanSupplier cancellationRequested) {
        return toTaskView(runStreamForView(taskId, userId, onEvent, cancellationRequested));
    }

    private BlogAgentTaskVO execute(Long taskId, Long userId, boolean streaming, Consumer<String> onDelta) {
        return execute(taskId, userId, streaming, (event, data) -> {
            if ("delta".equals(event)) onDelta.accept(data);
        }, () -> false);
    }

    private BlogAgentTaskVO execute(Long taskId, Long userId, boolean streaming, BiConsumer<String, String> onEvent) {
        return execute(taskId, userId, streaming, onEvent, () -> false);
    }

    private BlogAgentTaskVO execute(Long taskId, Long userId, boolean streaming,
                                    BiConsumer<String, String> onEvent,
                                    BooleanSupplier cancellationRequested) {
        transactionTemplate.executeWithoutResult(status -> claimInitialRun(taskId, userId));
        ScheduledFuture<?> leaseRenewal;
        try {
            leaseRenewal = startLeaseRenewal(taskId, userId);
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> markFailed(
                    taskId, userId, "博客生成助手执行服务繁忙，请稍后重试"));
            throw exception;
        }
        try {
            UsageAccumulator usage = new UsageAccumulator();
            checkCancellation(cancellationRequested);
            updateStage(taskId, userId, "analyze", "正在理解你的目标和文章边界", onEvent);
            ModelCallResult analysisCall = modelClient.analyze(getInput(taskId, userId));
            JsonNode analysis = analysisCall.payload();
            usage.add(analysisCall.usage(), analysisCall.model());
            checkCancellation(cancellationRequested);
            updateStage(taskId, userId, "research", "正在联网查找并核验相关资料", onEvent);
            ModelCallResult researchCall = modelClient.research(getInput(taskId, userId), analysis);
            JsonNode research = researchCall.payload();
            usage.add(researchCall.usage(), researchCall.model());
            checkCancellation(cancellationRequested);
            updateStage(taskId, userId, "write", "正在根据策划和调研结果撰写文章草稿", onEvent);
            String writingInput = getInput(taskId, userId)
                    + "\n\n策划结果：\n" + analysis
                    + "\n\n调研结果：\n" + research;
            ModelCallResult articleCall = streaming
                    ? modelClient.createArticleStream(writingInput, delta -> {
                        checkCancellation(cancellationRequested);
                        onEvent.accept("delta", delta);
                    })
                    : modelClient.createArticle(writingInput);
            JsonNode result = articleCall.payload();
            usage.add(articleCall.usage(), articleCall.model());
            checkCancellation(cancellationRequested);
            updateStage(taskId, userId, "illustrate", "正在按需生成并保存知识插图", onEvent);
            IllustrationOutcome outcome = generateIllustrations(
                    taskId, userId, result.path("content").asText(),
                    result.path("illustrations"), cancellationRequested);
            checkCancellation(cancellationRequested);
            updateStage(taskId, userId, "review", "正在进行最终逻辑与表达审校", onEvent);
            IllustrationOutcome reviewed = reviewOutcome(outcome, getInput(taskId, userId), usage);
            checkCancellation(cancellationRequested);
            return transactionTemplate.execute(status -> finalizeResult(taskId, userId, result, reviewed, usage,
                        BillingBiz.BLOG_AGENT_RUN, "task:" + taskId + ":run"));
        } catch (CancellationException exception) {
            transactionTemplate.executeWithoutResult(status -> markCancelled(taskId, userId));
            throw exception;
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> markFailed(taskId, userId, e.getMessage()));
            throw e;
        } finally {
            leaseRenewal.cancel(false);
        }
    }

    private void updateStage(Long taskId, Long userId, String stage, String message,
                             BiConsumer<String, String> onEvent) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                    .eq(BlogAgentTask::getId, taskId)
                    .eq(BlogAgentTask::getUserId, userId)
                    .eq(BlogAgentTask::getStatus, "running")
                    .set(BlogAgentTask::getStage, stage)
                    );
            requireActiveRun(taskId, userId, updated);
            saveMessage(taskId, "assistant", "process", stage, message);
        });
        onEvent.accept("stage", stage + "|" + message);
    }

    private IllustrationOutcome reviewOutcome(IllustrationOutcome outcome, String request, UsageAccumulator usage) {
        try {
            ModelCallResult call = modelClient.reviewArticle(outcome.content(), request);
            JsonNode reviewed = call.payload();
            usage.add(call.usage(), call.model());
            if (reviewed == null) return outcome;
            String content = defaultText(reviewed.path("content").asText(), outcome.content());
            return new IllustrationOutcome(content, outcome.status(), outcome.error(), outcome.generated());
        } catch (Exception e) {
            // 审校失败时保留原始稿件，属于有意的降级；记录原因便于排查。
            log.warn("blog-agent review stage degraded, keeping original content: {}", e.getMessage());
            return outcome;
        }
    }

    private void claimInitialRun(Long taskId, Long userId) {
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, taskId)
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "created")
                .set(BlogAgentTask::getStatus, "running")
                .set(BlogAgentTask::getStage, "analyze")
                .set(BlogAgentTask::getErrorMessage, null)
                );
        if (updated == 1) {
            eventService.clearTaskEvents(taskId);
            return;
        }

        BlogAgentTask task = requireOwnedTask(taskId, userId);
        if ("running".equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在执行，请勿重复启动");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务状态不能重新启动");
    }

    private String getInput(Long taskId, Long userId) {
        return requireOwnedTask(taskId, userId).getInput();
    }

    private BlogAgentTaskVO finalizeResult(Long taskId, Long userId, JsonNode result,
                                           IllustrationOutcome outcome, UsageAccumulator usage,
                                           String billingBizType, String billingBizKey) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        if (!"running".equals(task.getStatus())) {
            throw new IllegalStateException("博客生成助手任务执行权已失效");
        }
        applyResult(task, result);
        task.setContent(outcome.content());
        task.setIllustrationStatus(outcome.status());
        task.setIllustrationError(limit(outcome.error(), 1000));
        task.setStatus("ready");
        task.setStage("review");
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, taskId)
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "running")
                .set(BlogAgentTask::getInputType, task.getInputType())
                .set(BlogAgentTask::getAudience, task.getAudience())
                .set(BlogAgentTask::getTone, task.getTone())
                .set(BlogAgentTask::getTitle, task.getTitle())
                .set(BlogAgentTask::getSummary, task.getSummary())
                .set(BlogAgentTask::getContent, task.getContent())
                .set(BlogAgentTask::getOutline, task.getOutline())
                .set(BlogAgentTask::getContentBoundary, task.getContentBoundary())
                .set(BlogAgentTask::getKnowledgeGraphJson, task.getKnowledgeGraphJson())
                .set(BlogAgentTask::getCategoryId, task.getCategoryId())
                .set(BlogAgentTask::getTagsJson, task.getTagsJson())
                .set(BlogAgentTask::getReview, task.getReview())
                .set(BlogAgentTask::getIllustrationStatus, task.getIllustrationStatus())
                .set(BlogAgentTask::getIllustrationError, task.getIllustrationError())
                .set(BlogAgentTask::getStatus, "ready")
                .set(BlogAgentTask::getStage, "review")
                .set(BlogAgentTask::getErrorMessage, null)
                );
        requireActiveRun(taskId, userId, updated);
        saveSources(task.getId(), result.path("sources"));
        saveVersion(task, outcome.generated() > 0
                ? "博客生成助手完成调研、写作、插图与审校"
                : "博客生成助手完成调研、写作与审校");
        saveMessage(taskId, "assistant", "result", "review", task.getTitle());
        billingPort.chargeTokens(userId, billingBizType, billingBizKey,
                "task:" + taskId, usage.sum(), usage.model(), billingBizKey);
        return get(taskId, userId);
    }

    private void markFailed(Long taskId, Long userId, String errorMessage) {
        taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, taskId)
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "running")
                .set(BlogAgentTask::getStatus, "failed")
                .set(BlogAgentTask::getStage, "analyze")
                .set(BlogAgentTask::getErrorMessage, limit(errorMessage, 1000))
                );
    }

    private void markCancelled(Long taskId, Long userId) {
        taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, taskId)
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "running")
                .set(BlogAgentTask::getStatus, "failed")
                .set(BlogAgentTask::getErrorMessage, "已由智能体会话停止")
                );
    }

    /** Marks an initial task failed when its stream cannot enter the bounded worker pool. */
    public void failQueuedInitialRun(Long taskId, Long userId, String errorMessage) {
        taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, taskId)
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "created")
                .set(BlogAgentTask::getStatus, "failed")
                .set(BlogAgentTask::getStage, "analyze")
                .set(BlogAgentTask::getErrorMessage, limit(errorMessage, 1000))
                );
    }

    /** Records a rejected revision without changing the last verified article. */
    public void failQueuedRevision(Long taskId, Long userId, String errorMessage) {
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, taskId)
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "ready")
                .set(BlogAgentTask::getErrorMessage, limit(errorMessage, 1000))
                );
        if (updated == 1) {
            saveMessage(taskId, "assistant", "process", "review", "修改请求排队失败，已保留上一版文章");
        }
    }

    public BlogAgentTaskVO get(Long taskId, Long userId) {
        BlogAgentTask task = resolveStaleTask(requireOwnedTask(taskId, userId), userId);
        BlogAgentTaskVO vo = new BlogAgentTaskVO();
        vo.setTask(task);
        vo.setTags(readTags(task.getTagsJson()));
        vo.setContentBoundary(readObject(task.getContentBoundary()));
        vo.setKnowledgeGraph(readObject(task.getKnowledgeGraphJson()));
        vo.setSources(sourceMapper.selectList(new LambdaQueryWrapper<BlogAgentSource>()
                .eq(BlogAgentSource::getTaskId, taskId).orderByAsc(BlogAgentSource::getId)));
        vo.setVersions(versionMapper.selectList(new LambdaQueryWrapper<BlogAgentVersion>()
                .eq(BlogAgentVersion::getTaskId, taskId).orderByDesc(BlogAgentVersion::getVersionNo)));
        vo.setIllustrations(mediaAssetPort.listByTask(userId, taskId));
        vo.setMessages(messageMapper.selectList(new LambdaQueryWrapper<BlogAgentMessage>()
                .eq(BlogAgentMessage::getTaskId, taskId).orderByAsc(BlogAgentMessage::getId)));
        return vo;
    }

    BlogAgentTask resolveStaleTask(BlogAgentTask task, Long userId) {
        if (!"running".equals(task.getStatus()) || task.getUpdatedAt() == null) return task;
        int staleMinutes = Math.max(1, properties.getStaleTaskMinutes());
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(staleMinutes);
        if (task.getUpdatedAt().isAfter(staleBefore)) return task;

        boolean hasVerifiedVersion = versionMapper.selectCount(new LambdaQueryWrapper<BlogAgentVersion>()
                .eq(BlogAgentVersion::getTaskId, task.getId())) > 0;
        String status = hasVerifiedVersion ? "ready" : "failed";
        String message = hasVerifiedVersion
                ? "任务执行中断，已恢复到上一版已完成文章"
                : "任务执行中断，请重新创建任务";
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, task.getId())
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "running")
                .le(BlogAgentTask::getUpdatedAt, staleBefore)
                .set(BlogAgentTask::getStatus, status)
                .set(BlogAgentTask::getErrorMessage, message)
                );
        return updated == 1 ? requireOwnedTask(task.getId(), userId) : task;
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
        transactionTemplate.executeWithoutResult(status -> claimRevision(taskId, userId, instruction));
        ScheduledFuture<?> leaseRenewal;
        try {
            leaseRenewal = startLeaseRenewal(taskId, userId);
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> markRevisionFailed(
                    taskId, userId, "博客生成助手执行服务繁忙，请稍后重试"));
            throw exception;
        }
        try {
            UsageAccumulator usage = new UsageAccumulator();
            updateStage(taskId, userId, "write", "正在根据你的新要求修改文章", onEvent);
            ModelCallResult reviseCall = modelClient.reviseArticleStream(
                    requireOwnedTask(taskId, userId).getContent(), instruction,
                    delta -> onEvent.accept("delta", delta));
            JsonNode result = reviseCall.payload();
            usage.add(reviseCall.usage(), reviseCall.model());
            updateStage(taskId, userId, "illustrate", "正在同步调整文章插图", onEvent);
            IllustrationOutcome outcome = generateIllustrations(
                    taskId, userId, result.path("content").asText(), result.path("illustrations"));
            updateStage(taskId, userId, "review", "正在检查本轮修改", onEvent);
            IllustrationOutcome reviewed = reviewOutcome(outcome, instruction, usage);
            return transactionTemplate.execute(status -> finalizeResult(taskId, userId, result, reviewed,
                    usage, BillingBiz.BLOG_AGENT_REVISE, "task:" + taskId + ":revise"));
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> markRevisionFailed(taskId, userId, e.getMessage()));
            throw e;
        } finally {
            leaseRenewal.cancel(false);
        }
    }

    private void claimRevision(Long taskId, Long userId, String instruction) {
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, taskId)
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "ready")
                .set(BlogAgentTask::getStatus, "running")
                .set(BlogAgentTask::getStage, "write")
                .set(BlogAgentTask::getErrorMessage, null)
                );
        if (updated == 1) {
            eventService.clearTaskEvents(taskId);
            saveMessage(taskId, "user", "message", null, instruction.trim());
            return;
        }

        BlogAgentTask task = requireOwnedTask(taskId, userId);
        if ("running".equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在执行，请勿重复提交修改");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前文章尚未准备好，不能修改");
    }

    private void markRevisionFailed(Long taskId, Long userId, String errorMessage) {
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getId, taskId)
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getStatus, "running")
                .set(BlogAgentTask::getStatus, "ready")
                .set(BlogAgentTask::getStage, "review")
                .set(BlogAgentTask::getErrorMessage, limit(errorMessage, 1000))
                );
        if (updated == 1) {
            saveMessage(taskId, "assistant", "process", "review", "本轮修改失败，已保留上一版文章");
        }
    }

    private void saveMessage(Long taskId, String role, String kind, String stage, String content) {
        BlogAgentMessage message = new BlogAgentMessage();
        message.setTaskId(taskId);
        message.setRole(role);
        message.setKind(kind);
        message.setStage(stage);
        message.setContent(defaultText(content, ""));
        messageMapper.insert(message);
    }

    private IllustrationOutcome generateIllustrations(Long taskId, Long userId,
                                                       String articleContent, JsonNode plans) {
        return generateIllustrations(taskId, userId, articleContent, plans, () -> false);
    }

    private IllustrationOutcome generateIllustrations(Long taskId, Long userId,
                                                       String articleContent, JsonNode plans,
                                                       BooleanSupplier cancellationRequested) {
        requireRunningTask(taskId, userId);
        String content = articleContent;
        if (!plans.isArray() || plans.isEmpty()) {
            return new IllustrationOutcome(removeIllustrationPlaceholders(content), "none", "", 0);
        }
        if (!imageClient.isEnabled()) {
            return new IllustrationOutcome(removeIllustrationPlaceholders(content), "disabled",
                    "未配置插图生成模型（功能模型配置），已跳过插图生成", 0);
        }

        int generatedCount = 0;
        List<String> errors = new ArrayList<>();
        int limit = Math.max(0, Math.min(properties.getMaxIllustrations(), 3));
        for (int index = 0; index < Math.min(plans.size(), limit); index++) {
            // Image/model calls are not forcibly interrupted; stop at the next safe stage boundary.
            checkCancellation(cancellationRequested);
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
                // Reuse an existing agent image with the same illustration
                // title before spending another model generation on it.
                MediaAssetRef reused = mediaAssetPort.findReusableGenerated(userId, title);
                String markdown;
                if (reused != null) {
                    markdown = "![" + alt + "](" + reused.url() + ")";
                } else {
                    BlogAgentImageClient.GeneratedImage generated = imageClient.generate(
                            "为中文知识博客生成一张准确、克制、专业的知识插图。画面必须服务于理解，不要添加水印。"
                                    + "如果包含文字，确保文字简短清晰。插图要求：" + prompt);
                    String fileName = "agent-" + taskId + "-" + (index + 1) + "." + generated.extension();
                    String meta = objectMapper.writeValueAsString(java.util.Map.of(
                            "title", title,
                            "alt", alt,
                            "prompt", prompt,
                            "model", generated.model(),
                            "size", properties.getImageSize()));
                    MediaAssetRef asset = mediaAssetPort.recordGenerated(userId, taskId, fileName,
                            generated.bytes(), generated.contentType(), meta);
                    markdown = "![" + alt + "](" + asset.url() + ")";
                    billingPort.chargeTokens(userId, BillingBiz.BLOG_AGENT_ILLUSTRATION,
                            "illustration:" + taskId + ":" + (index + 1), "task:" + taskId,
                            billable(generated.usage()), generated.model(), "知识插图生成");
                }
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

    /** Keeps long remote stages from being mistaken for abandoned work. */
    private ScheduledFuture<?> startLeaseRenewal(Long taskId, Long userId) {
        long staleSeconds = Math.max(60L, Math.max(1, properties.getStaleTaskMinutes()) * 60L);
        long intervalSeconds = Math.max(1L, Math.min(MAX_LEASE_RENEW_INTERVAL_SECONDS, staleSeconds / 3L));
        return leaseScheduler.scheduleAtFixedRate(() -> {
            try {
                int renewed = taskMapper.update(null, new LambdaUpdateWrapper<BlogAgentTask>()
                        .eq(BlogAgentTask::getId, taskId)
                        .eq(BlogAgentTask::getUserId, userId)
                        .eq(BlogAgentTask::getStatus, "running")
                        );
                if (renewed != 1) {
                    log.debug("blog-agent lease renewal stopped for inactive task {}", taskId);
                    throw new BlogAgentRunSupersededException(taskId);
                }
            } catch (BlogAgentRunSupersededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn("blog-agent lease renewal failed for task {}: {}",
                        taskId, exception.getClass().getSimpleName());
            }
        }, intervalSeconds);
    }

    private BlogAgentTask requireRunningTask(Long taskId, Long userId) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        if (!"running".equals(task.getStatus())) {
            throw new BlogAgentRunSupersededException(taskId);
        }
        return task;
    }

    private void requireActiveRun(Long taskId, Long userId, int updated) {
        if (updated != 1) {
            requireOwnedTask(taskId, userId);
            throw new BlogAgentRunSupersededException(taskId);
        }
    }

    private void checkCancellation(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException("用户已停止当前智能体任务");
        }
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

    /**
     * 前端 HTTP 层的发布入口，返回完整的 {@code BlogPostVO}。
     *
     * <p>端口那一侧走 {@link #publish(Long, Long)}。{@code ForView} 后缀的受众就是这里：
     * {@code BlogAgentController#publish} 把 {@code BlogPostVO} 直接当响应体，
     * 换掉它就是这个前端路由的响应体变更，属于契约变更。</p>
     */
    public BlogPostVO publishForView(Long taskId, Long userId) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        if (!"ready".equals(task.getStatus())) throw new IllegalStateException("文章尚未生成完成");
        if (task.getPublishedPostId() != null) return blogService.getBlogById(task.getPublishedPostId());
        var result = publicationService.publish(new PublishCommand(taskId, userId, task.getTitle(),
                task.getSummary(), task.getContent(), task.getCategoryId(), null, readTags(task.getTagsJson())), "local");
        BlogPostVO published = blogService.getBlogById(Long.valueOf(result.externalId()));
        task.setPublishedPostId(published.getId());
        taskMapper.updateById(task);
        return published;
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ <b>参数是换位的</b>：端口收 {@code (userId, taskId)}，上面那个方法收
     * {@code (taskId, userId)}。</p>
     *
     * <p>这里没有 {@code @Transactional}，因为兄弟方法也没有 —— 发布链路自己的事务在
     * {@code publicationService} 与 {@code blogService} 里面。补一个裸 {@code @Transactional}
     * 不是"更安全"，而是把两条外部调用路径的边界改掉了。</p>
     */
    @Override
    public BlogAgentPostView publish(Long userId, Long taskId) {
        return toPostView(publishForView(taskId, userId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>这个方法<b>直接就是端口实现</b>，没有 {@code ForView} 兄弟：它唯一的调用方是跨域的
     * {@code PublishPostTool}，同域没有任何人读它的 {@code BlogPostVO} 形态，所以
     * 直接换返回类型、直接把参数归一成 {@code (userId, taskId)}。给一个没有调用方的富方法留后缀
     * 是死代码，而"删掉它不会有别的症状"这件事由全量测试守着（{@code BlogAgentController}
     * 从不调用它）。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public BlogAgentPostView findPublishedPost(Long userId, Long taskId) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        return task.getPublishedPostId() == null ? null
                : toPostView(blogService.getBlogById(task.getPublishedPostId()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>图片能力与任务能力<b>共用同一个实现类</b>，但走的是两个端口：提供方是
     * {@code BlogAgentImageClient}（一个具体客户端），不是本类自己的领域方法。
     * 这一行就是全部 —— 端口只把"能力"过界。</p>
     */
    @Override
    public boolean isEnabled() {
        return imageClient.isEnabled();
    }

    /**
     * {@inheritDoc}
     *
     * <p>换算落在这一处：{@code BlogAgentImageClient.GeneratedImage}（域内类型，带
     * {@code ModelUsage}）→ {@link BlogAgentImage}（契约类型，带 {@link BlogAgentImageUsage}）。
     * 两者前五个分量<b>逐位同名同序</b>，所以这是一次位置映射；{@code size} 是契约侧新增的，
     * 由这里从 {@code properties.getImageSize()} 填 —— <b>这正是"消费方不必读
     * {@code @ConfigurationProperties}"的意思</b>：配置项留在提供方，只有它的取值过界。</p>
     *
     * <p>⚠️ {@code model} 取 {@code generated.model()}，<b>不是</b>
     * {@code properties.getImageModel()}。客户端会在候选端点之间兜底重试，真正产出这张图的
     * 可能是兜底端点；而走管理台网关路径时 {@code properties.getImageModel()} 甚至可能是空的。
     * 同域的插图路径（{@code generateIllustrations}）一直是写 {@code generated.model()} 的，
     * 跨域消费方必须用同一个口径。</p>
     */
    @Override
    public BlogAgentImage generate(String prompt) {
        BlogAgentImageClient.GeneratedImage generated = imageClient.generate(prompt);
        ModelUsage usage = generated.usage();
        return new BlogAgentImage(generated.bytes(), generated.contentType(), generated.extension(),
                usage == null ? BlogAgentImageUsage.NONE
                        : new BlogAgentImageUsage(usage.inputTokens(), usage.cachedTokens(),
                                usage.outputTokens()),
                generated.model(), properties.getImageSize());
    }

    private BlogAgentTask requireOwnedTask(Long taskId, Long userId) {
        BlogAgentTask task = taskMapper.selectById(taskId);
        if (task == null || userId == null || !userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("博客生成助手任务不存在");
        }
        return task;
    }

    private BlogAgentTask findTaskByClientRequestId(Long userId, String clientRequestId) {
        if (userId == null || clientRequestId == null) return null;
        return taskMapper.selectOne(new LambdaQueryWrapper<BlogAgentTask>()
                .eq(BlogAgentTask::getUserId, userId)
                .eq(BlogAgentTask::getClientRequestId, clientRequestId)
                .last("LIMIT 1"));
    }

    private String normalizeClientRequestId(String clientRequestId) {
        if (!StringUtils.hasText(clientRequestId)) return null;
        String normalized = clientRequestId.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("请求幂等标识不能超过 64 个字符");
        }
        return normalized;
    }

    /** Runs the compliance gate against a ready article. A model outage is
     * treated as reject (fail closed) so scheduled auto-publishing never ships
     * unvetted content. */
    public ComplianceReview reviewCompliance(Long taskId, Long userId) {
        BlogAgentTask task = requireOwnedTask(taskId, userId);
        if (!"ready".equals(task.getStatus())) {
            throw new IllegalStateException("文章尚未生成完成，无法进行合规审核");
        }
        if (!StringUtils.hasText(task.getContent())) {
            throw new IllegalStateException("文章内容为空，无法进行合规审核");
        }
        try {
            ModelCallResult call = modelClient.reviewCompliance(
                    defaultText(task.getTitle(), ""), task.getContent());
            JsonNode result = call.payload();
            boolean pass = result.path("pass").asBoolean(false);
            String reason = result.path("reasons").isArray()
                    ? result.path("reasons").toString()
                    : "";
            billingPort.chargeTokens(userId, BillingBiz.BLOG_AGENT_COMPLIANCE,
                    "task:" + taskId + ":compliance", "task:" + taskId,
                    billable(call.usage()), call.model(), "合规审查");
            return new ComplianceReview(pass, reason);
        } catch (RuntimeException e) {
            log.warn("blog-agent compliance review degraded to reject for task {}: {}",
                    taskId, e.getMessage());
            return new ComplianceReview(false, "合规审核服务暂不可用，本次未自动发布");
        }
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
        versionMapper.insert(version);
    }

    private List<String> readTags(String json) {
        try { return objectMapper.readValue(defaultText(json, "[]"), new TypeReference<List<String>>() {}); }
        catch (Exception e) { log.debug("blog-agent task tags are not a JSON array: {}", e.getMessage()); return List.of(); }
    }

    private java.util.Map<String, Object> readObject(String json) {
        try { return objectMapper.readValue(defaultText(json, "{}"), new TypeReference<java.util.Map<String, Object>>() {}); }
        catch (Exception e) { log.debug("blog-agent task object payload is not valid JSON: {}", e.getMessage()); return java.util.Map.of(); }
    }

    private String writeNode(JsonNode node) {
        try { return node != null && node.isObject() ? objectMapper.writeValueAsString(node) : "{}"; }
        catch (Exception e) { log.debug("blog-agent task node serialization failed: {}", e.getMessage()); return "{}"; }
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

    // --- 契约换算。每个类型只有一处落点，端口方法与富方法共用它 ---
    //
    // 为什么必须只有一处：这些方法逐字段对齐两个类型，是"分量顺序写反"的唯一防线。
    // 分散成两处意味着以后加一个分量要记得改两遍，而漏掉一处不会有任何编译错误。

    /**
     * 域实体 → 契约视图。
     *
     * <p>{@code null} 透传：{@code findReadyTaskByClientRequestIdForView} 用 {@code null} 表示
     * "没有已完成的任务"，那是**有意义的结果**，不能在这里变成一个空对象。</p>
     */
    /**
     * 域实体 → 契约视图。
     *
     * <p>只带 4 个分量：两个消费方（{@code BlogGenerationTool}）读的就是这 4 个。
     * 域实体是 25 个字段的 {@code @TableName} 实体，不许跨边界。</p>
     */
    private static BlogAgentTaskView toTaskView(BlogAgentTask task) {
        if (task == null) return null;
        return new BlogAgentTaskView(task.getId(), task.getTitle(), task.getSummary(),
                task.getPublishedPostId());
    }

    /**
     * {@code BlogAgentTaskVO} → 契约视图。
     *
     * <p>为什么有两个入口：{@code create} 与 {@code findReadyTaskByClientRequestIdForView}
     * 返回实体，而 {@code runStreamForView} 返回 VO（它顺带带了 tags / sources / versions 等
     * 前端要的东西）。VO 只是实体的包装，所以这里剥一层再走上面那个方法 ——
     * <b>换算规则仍然只有一份</b>，不是把 4 个字段抄两遍。</p>
     *
     * <p>VO 为 {@code null} 时透传 {@code null}；VO 非空但 {@code task} 为空是<b>不该发生</b>的
     * （{@code execute} 的两条返回路径都经 {@code get()}，那里一定 {@code setTask}），
     * 所以不在这里兜底 —— 兜底会把这个不变量变成"没人知道它是否成立"。</p>
     */
    private static BlogAgentTaskView toTaskView(BlogAgentTaskVO vo) {
        return vo == null ? null : toTaskView(vo.getTask());
    }

    /** {@code BlogPostVO} → 契约视图。{@code null} 透传，理由同上。 */
    private static BlogAgentPostView toPostView(BlogPostVO post) {
        if (post == null) return null;
        return new BlogAgentPostView(post.getId(), post.getTitle(), post.getStatus(),
                post.getCsdnSynced(), post.getJuejinSynced());
    }

    /**
     * 契约草稿 → 域请求体。
     *
     * <p><b>纯换算，不兜底。</b> {@code clientRequestId} 缺省时必须落成 {@code null} ——
     * 域侧用 {@code null} 表示"这次调用不要求幂等"。给它填一个默认值（比如空串或
     * invocation id）会让每一次没有显式传幂等键的调用**静默变成幂等调用**，
     * 而症状是"第二次生成被当成重复请求吃掉了"。</p>
     */
    private static BlogAgentTaskCreateRequest toRequest(BlogAgentTaskDraft draft) {
        if (draft == null) return null;
        BlogAgentTaskCreateRequest request = new BlogAgentTaskCreateRequest();
        request.setInput(draft.input());
        request.setClientRequestId(draft.clientRequestId());
        return request;
    }

    private record IllustrationOutcome(String content, String status, String error, int generated) {}

    /**
     * 网关口径 {@code ModelUsage} -> 计费契约口径 {@link TokenUsage}。
     *
     * <p>两者分量同名同序，所以这是一次直接映射，没有"漏了哪个字段"的可能。换算之所以
     * 必须落在调用方，是因为契约不能依赖带 Jackson 的 {@code libs/xander-model}（
     * 契约必须与序列化框架无关）。</p>
     *
     * <p>{@code null} 归一到 {@link TokenUsage#NONE}：网关没返回用量时端口按"空用量"处理
     * （插图类会因此拒绝扣费，其它业务退回一口价），而不是抛 NPE。</p>
     */
    private static TokenUsage billable(ModelUsage usage) {
        return usage == null ? TokenUsage.NONE
                : new TokenUsage(usage.inputTokens(), usage.cachedTokens(), usage.outputTokens());
    }

    /** Aggregates token usage across the sequential stages of one blog-agent run. */
    private static final class UsageAccumulator {
        private long input;
        private long cached;
        private long output;
        private String model;
        private boolean mixedModels;

        void add(ModelUsage usage, String usedModel) {
            if (usage == null) return;
            input += Math.max(0L, usage.inputTokens());
            cached += Math.max(0L, usage.cachedTokens());
            output += Math.max(0L, usage.outputTokens());
            if (StringUtils.hasText(usedModel)) {
                if (model == null) model = usedModel;
                else if (!model.equals(usedModel)) mixedModels = true;
            }
        }

        /**
         * 计费契约口径的汇总（分量同名同序，换算按访问器取、按位置放）。
         *
         * <p>累加仍用网关口径 {@code ModelUsage}：它是 {@code add()} 的入参类型，
         * 换掉会让这个内部累加器依赖契约，而它并不需要。</p>
         */
        TokenUsage sum() {
            return new TokenUsage(input, cached, output);
        }

        String model() {
            return mixedModels ? null : model;
        }
    }

    private static final class BlogAgentRunSupersededException extends IllegalStateException {
        private BlogAgentRunSupersededException(Long taskId) {
            super("博客生成助手任务执行权已失效：task=" + taskId);
        }
    }
}
