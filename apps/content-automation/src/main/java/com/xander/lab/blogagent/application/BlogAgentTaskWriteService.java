package com.xander.lab.blogagent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.api.BlogAgentTaskCreateRequest;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.core.api.BlogAgentModelContract.Response;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.infrastructure.BlogAgentModelClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Durable BlogAgent command owner for content-automation.
 *
 * <p>The model is still executed by core-host through a stateless internal API because the admin
 * model gateway has not been extracted yet. Every task mutation, child row, and failure transition
 * in this service is nevertheless written to content-automation's datasource.</p>
 */
@Service
public class BlogAgentTaskWriteService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final BlogAgentModelClient modelClient;
    private final BlogAgentTaskReadService readService;

    public BlogAgentTaskWriteService(JdbcTemplate jdbcTemplate,
                                     TransactionTemplate transactionTemplate,
                                     ObjectMapper objectMapper,
                                     BlogAgentModelClient modelClient,
                                     BlogAgentTaskReadService readService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.modelClient = modelClient;
        this.readService = readService;
    }

    public BlogAgentTask create(long userId, BlogAgentTaskCreateRequest request) {
        if (userId <= 0 || request == null || !StringUtils.hasText(request.getInput())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入主题或日记");
        }
        String input = request.getInput().trim();
        String clientRequestId = normalizeClientRequestId(request.getClientRequestId());
        if (clientRequestId != null) {
            BlogAgentTask existing = readService.findByClientRequestId(userId, clientRequestId);
            if (existing != null) return existing;
        }
        try {
            return transactionTemplate.execute(status -> {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO blog_agent_task"
                                    + " (user_id, client_request_id, input, input_type, audience, tone,"
                                    + " status, stage, tags_json) VALUES (?, ?, ?, 'pending', '', '',"
                                    + " 'created', 'analyze', '[]')",
                            Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, userId);
                    statement.setString(2, clientRequestId);
                    statement.setString(3, input);
                    return statement;
                }, keyHolder);
                Number key = keyHolder.getKey();
                if (key == null) throw new IllegalStateException("博客生成助手任务创建失败");
                long taskId = key.longValue();
                insertMessage(taskId, "user", "message", null, input);
                return readService.findOwnedTask(userId, taskId);
            });
        } catch (DuplicateKeyException duplicate) {
            BlogAgentTask existing = readService.findByClientRequestId(userId, clientRequestId);
            if (existing != null) return existing;
            throw duplicate;
        }
    }

    public BlogAgentTaskVO run(long userId, long taskId) {
        return run(userId, taskId, (event, data) -> { });
    }

    public BlogAgentTaskVO run(long userId, long taskId,
                               BiConsumer<String, String> observer) {
        claimInitialRun(userId, taskId);
        try {
            BlogAgentTask task = requireOwnedTask(userId, taskId);
            String input = task.getInput();
            updateStage(userId, taskId, "analyze", "正在理解你的目标和文章边界", observer);
            Response analysis = modelClient.analyze(input);
            JsonNode analysisPayload = requirePayload(analysis);
            updateStage(userId, taskId, "research", "正在联网查找并核验相关资料", observer);
            Response research = modelClient.research(input, analysisPayload);
            JsonNode researchPayload = requirePayload(research);
            updateStage(userId, taskId, "write", "正在根据策划和调研结果撰写文章草稿", observer);
            String writingInput = input
                    + "\n\n策划结果：\n" + analysisPayload
                    + "\n\n调研结果：\n" + researchPayload;
            Response article = modelClient.article(writingInput);
            JsonNode articlePayload = requirePayload(article);
            updateStage(userId, taskId, "review", "正在进行最终逻辑与表达审校", observer);
            Response review = modelClient.review(articlePayload.path("content").asText(), input);
            return finalizeReady(userId, taskId, articlePayload, review);
        } catch (RuntimeException exception) {
            markFailed(userId, taskId, exception.getMessage());
            throw exception;
        }
    }

    public BlogAgentTaskVO revise(long userId, long taskId, String instruction,
                                  BiConsumer<String, String> observer) {
        if (!StringUtils.hasText(instruction)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "修改要求不能为空");
        }
        claimRevision(userId, taskId, instruction.trim());
        try {
            String currentArticle = requireOwnedTask(userId, taskId).getContent();
            updateStage(userId, taskId, "write", "正在根据你的新要求修改文章", observer);
            Response revision = modelClient.revise(currentArticle, instruction.trim());
            JsonNode articlePayload = requirePayload(revision);
            updateStage(userId, taskId, "review", "正在检查本轮修改", observer);
            Response review = modelClient.review(articlePayload.path("content").asText(), instruction.trim());
            return finalizeReady(userId, taskId, articlePayload, review);
        } catch (RuntimeException exception) {
            markRevisionFailed(userId, taskId, exception.getMessage());
            throw exception;
        }
    }

    private void claimInitialRun(long userId, long taskId) {
        Integer updated = transactionTemplate.execute(status -> jdbcTemplate.update(
                "UPDATE blog_agent_task SET status = 'running', stage = 'analyze', error_message = NULL"
                        + " WHERE id = ? AND user_id = ? AND status = 'created'",
                taskId, userId));
        if (updated != null && updated == 1) return;
        BlogAgentTask task = requireOwnedTask(userId, taskId);
        if ("running".equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在执行，请勿重复启动");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务状态不能重新启动");
    }

    private void updateStage(long userId, long taskId, String stage, String message,
                             BiConsumer<String, String> observer) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update(
                    "UPDATE blog_agent_task SET stage = ? WHERE id = ? AND user_id = ? AND status = 'running'",
                    stage, taskId, userId);
            if (updated != 1) throw new IllegalStateException("博客生成助手执行权已失效");
            insertMessage(taskId, "assistant", "process", stage, message);
        });
        observer.accept("stage", stage + "|" + message);
    }

    private void claimRevision(long userId, long taskId, String instruction) {
        Integer updated = transactionTemplate.execute(status -> jdbcTemplate.update(
                "UPDATE blog_agent_task SET status = 'running', stage = 'write', error_message = NULL"
                        + " WHERE id = ? AND user_id = ? AND status = 'ready'",
                taskId, userId));
        if (updated != null && updated == 1) {
            transactionTemplate.executeWithoutResult(status ->
                    insertMessage(taskId, "user", "message", null, instruction));
            return;
        }
        BlogAgentTask task = requireOwnedTask(userId, taskId);
        if ("running".equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务正在执行，请勿重复提交修改");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前文章尚未准备好，不能修改");
    }

    private void markRevisionFailed(long userId, long taskId, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update(
                    "UPDATE blog_agent_task SET status = 'ready', stage = 'review', error_message = ?"
                            + " WHERE id = ? AND user_id = ? AND status = 'running'",
                    limit(defaultText(errorMessage, "本轮修改失败"), 1000), taskId, userId);
            if (updated == 1) {
                insertMessage(taskId, "assistant", "process", "review", "本轮修改失败，已保留上一版文章");
            }
        });
    }

    private BlogAgentTaskVO finalizeReady(long userId, long taskId,
                                          JsonNode article, Response review) {
        return transactionTemplate.execute(status -> {
            BlogAgentTask task = requireOwnedTask(userId, taskId);
            if (!"running".equals(task.getStatus())) {
                throw new IllegalStateException("博客生成助手执行权已失效");
            }
            String title = required(article, "title");
            String content = required(article, "content");
            JsonNode reviewed = review == null ? null : requirePayload(review);
            if (reviewed != null && StringUtils.hasText(reviewed.path("content").asText())) {
                content = reviewed.path("content").asText();
            }
            String summary = defaultText(article.path("summary").asText(), excerpt(content, 160));
            String reviewText = reviewed == null ? "" : reviewed.path("review").asText("");
            String tags = writeArray(article.path("tags"));
            int updated = jdbcTemplate.update(
                    "UPDATE blog_agent_task SET input_type = ?, audience = ?, tone = ?, title = ?,"
                            + " summary = ?, content = ?, outline = ?, content_boundary = ?,"
                            + " knowledge_graph_json = ?, category_id = ?, tags_json = ?, review = ?,"
                            + " illustration_status = 'none', illustration_error = NULL, status = 'ready',"
                            + " stage = 'review', error_message = NULL WHERE id = ? AND user_id = ?"
                            + " AND status = 'running'",
                    normalizeInputType(article.path("writingBrief").path("inputNature").asText()),
                    limit(defaultText(article.path("writingBrief").path("audience").asText(), "广泛读者"), 120),
                    limit(defaultText(article.path("writingBrief").path("tone").asText(), "清晰、准确、可读"), 60),
                    limit(title, 255), summary, removeIllustrationPlaceholders(content),
                    article.path("outline").asText(""), writeObject(article.path("contentBoundary")),
                    writeObject(article.path("knowledgeGraph")), normalizeCategory(article.path("categoryId").asText()),
                    tags, reviewText, taskId, userId);
            if (updated != 1) throw new IllegalStateException("博客生成助手执行权已失效");
            saveSources(taskId, article.path("sources"));
            saveVersion(taskId, title, summary, removeIllustrationPlaceholders(content),
                    "博客生成助手完成调研、写作与审校");
            insertMessage(taskId, "assistant", "result", "review", title);
            return readService.detail(userId, taskId);
        });
    }

    private void markFailed(long userId, long taskId, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE blog_agent_task SET status = 'failed', stage = 'analyze', error_message = ?"
                        + " WHERE id = ? AND user_id = ? AND status = 'running'",
                limit(defaultText(errorMessage, "博客生成助手执行失败"), 1000), taskId, userId));
    }

    private void saveSources(long taskId, JsonNode sourceNodes) {
        if (sourceNodes == null || !sourceNodes.isArray()) return;
        jdbcTemplate.update("DELETE FROM blog_agent_source WHERE task_id = ?", taskId);
        for (JsonNode node : sourceNodes) {
            String url = node.path("url").asText();
            if (!StringUtils.hasText(url) || !url.startsWith("http") || url.length() > 2000) continue;
            jdbcTemplate.update("INSERT INTO blog_agent_source"
                            + " (task_id, title, url, publisher, excerpt, reliability) VALUES (?, ?, ?, ?, ?, ?)",
                    taskId, limit(defaultText(node.path("title").asText(), url), 500), url,
                    limit(node.path("publisher").asText(""), 255), node.path("excerpt").asText(""),
                    limit(defaultText(node.path("reliability").asText(), "未标注"), 64));
        }
    }

    private void saveVersion(long taskId, String title, String summary, String content, String note) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blog_agent_version WHERE task_id = ?", Integer.class, taskId);
        jdbcTemplate.update("INSERT INTO blog_agent_version"
                        + " (task_id, version_no, title, summary, content, change_note) VALUES (?, ?, ?, ?, ?, ?)",
                taskId, (count == null ? 0 : count) + 1, title, summary, content, note);
    }

    private void insertMessage(long taskId, String role, String kind, String stage, String content) {
        jdbcTemplate.update("INSERT INTO blog_agent_message"
                        + " (task_id, role, kind, stage, content) VALUES (?, ?, ?, ?, ?)",
                taskId, role, kind, stage, defaultText(content, ""));
    }

    private BlogAgentTask requireOwnedTask(long userId, long taskId) {
        BlogAgentTask task = readService.findOwnedTask(userId, taskId);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "博客生成助手任务不存在");
        return task;
    }

    private JsonNode requirePayload(Response response) {
        if (response == null || response.payloadJson() == null || response.payloadJson().isBlank()) {
            throw new IllegalStateException("博客模型没有返回阶段结果");
        }
        try {
            JsonNode payload = objectMapper.readTree(response.payloadJson());
            if (payload == null || payload.isNull()) throw new IllegalStateException();
            return payload;
        } catch (Exception exception) {
            throw new IllegalStateException("博客模型阶段结果不是有效 JSON", exception);
        }
    }

    private String writeArray(JsonNode node) {
        try {
            List<String> values = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(value -> {
                    if (StringUtils.hasText(value.asText()) && values.size() < 8) values.add(value.asText().trim());
                });
            }
            return objectMapper.writeValueAsString(values.stream().distinct().toList());
        } catch (Exception exception) {
            return "[]";
        }
    }

    private String writeObject(JsonNode node) {
        try {
            return node != null && node.isObject() ? objectMapper.writeValueAsString(node) : "{}";
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String normalizeClientRequestId(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        if (normalized.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求幂等标识不能超过 64 个字符");
        }
        return normalized;
    }

    private String normalizeCategory(String value) {
        return List.of("frontend", "backend", "architecture", "devops", "career").contains(value)
                ? value : "career";
    }

    private String normalizeInputType(String value) {
        return List.of("topic", "project_context", "journal").contains(value) ? value : "topic";
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!StringUtils.hasText(value)) throw new IllegalStateException("模型未返回 " + field);
        return value;
    }

    private String removeIllustrationPlaceholders(String value) {
        return value.replaceAll("(?m)^\\s*<!-- illustration:[a-z0-9-]+ -->\\s*$", "").trim();
    }

    private static String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String excerpt(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }

    private static String limit(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
