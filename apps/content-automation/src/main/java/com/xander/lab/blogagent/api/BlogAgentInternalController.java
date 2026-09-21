package com.xander.lab.blogagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.blogagent.application.BlogAgentPublishService;
import com.xander.lab.blogagent.application.BlogAgentTaskReadService;
import com.xander.lab.blogagent.application.BlogAgentTaskWriteService;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.publishing.api.BlogPostVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Token-protected Agent → Content BlogAgent boundary. */
@RestController
@RequestMapping("/internal/agent/blogagent")
@ConditionalOnProperty(prefix = "content-automation", name = "agent-blogagent-api-enabled",
        havingValue = "true")
public class BlogAgentInternalController {

    private static final String TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final BlogAgentTaskWriteService writeService;
    private final BlogAgentTaskReadService readService;
    private final BlogAgentPublishService publishService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final String internalToken;

    public BlogAgentInternalController(BlogAgentTaskWriteService writeService,
                                       BlogAgentTaskReadService readService,
                                       BlogAgentPublishService publishService,
                                       ObjectMapper objectMapper,
                                       @Qualifier("contentBlogAgentStreamExecutor") TaskExecutor executor,
                                       ContentAutomationProperties properties) {
        if (properties.getAgentInternalToken() == null || properties.getAgentInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "content-automation.agent-internal-token 在启用 Agent BlogAgent API 时不能为空");
        }
        this.writeService = writeService;
        this.readService = readService;
        this.publishService = publishService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.internalToken = properties.getAgentInternalToken();
    }

    @PostMapping("/create")
    public BlogAgentTask create(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                @RequestBody CreateRequest request) {
        authorize(token);
        requireRequest(request);
        BlogAgentTaskCreateRequest input = new BlogAgentTaskCreateRequest();
        input.setInput(request.input());
        input.setClientRequestId(request.clientRequestId());
        return writeService.create(request.userId(), input);
    }

    @PostMapping(value = "/{taskId}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                          @PathVariable long taskId,
                          @RequestBody UserRequest request) {
        authorize(token);
        requireUser(request);
        SseEmitter emitter = new SseEmitter(0L);
        try {
            executor.execute(() -> {
                try {
                    BlogAgentTaskVO task = writeService.run(request.userId(), taskId,
                            (event, data) -> send(emitter, event, data));
                    send(emitter, "complete", objectMapper.writeValueAsString(task));
                    emitter.complete();
                } catch (Exception exception) {
                    try {
                        send(emitter, "error", safeMessage(exception));
                    } finally {
                        emitter.complete();
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "博客生成助手流式执行队列繁忙", exception);
        }
        return emitter;
    }

    @PostMapping("/find-ready")
    public BlogAgentTask findReady(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                   @RequestBody UserRequest request) {
        authorize(token);
        requireUser(request);
        return readService.findReadyByClientRequestId(request.userId(), request.clientRequestId());
    }

    @PostMapping("/find-published")
    public BlogPostVO findPublished(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                    @RequestBody UserRequest request) {
        authorize(token);
        requireUser(request);
        return publishService.findPublishedPost(request.userId(), request.taskId());
    }

    @PostMapping("/{taskId}/publish")
    public BlogPostVO publish(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                             @PathVariable long taskId,
                             @RequestBody UserRequest request) {
        authorize(token);
        requireUser(request);
        return publishService.publish(request.userId(), taskId);
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    private void requireRequest(CreateRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "博客生成助手请求参数非法");
        }
    }

    private void requireUser(UserRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "博客生成助手请求参数非法");
        }
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException exception) {
            throw new IllegalStateException("博客生成助手内网流已断开", exception);
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "博客生成助手执行失败" : message;
    }

    public record CreateRequest(Long userId, String input, String clientRequestId) {
    }

    public record UserRequest(Long userId, Long taskId, String clientRequestId) {
    }
}
