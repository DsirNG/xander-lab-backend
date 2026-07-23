package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.agent.BlogAgentTaskCreateRequest;
import com.xander.lab.dto.agent.BlogAgentTaskVO;
import com.xander.lab.dto.agent.BlogAgentMessageRequest;
import com.xander.lab.dto.agent.BlogAgentSessionVO;
import com.xander.lab.dto.BlogPostVO;
import com.xander.lab.entity.BlogAgentTask;
import com.xander.lab.service.BlogAgentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.List;

@RestController
@RequestMapping("/api/blog-agent/tasks")
public class BlogAgentController {
    private final BlogAgentService service;
    private final TaskExecutor blogAgentTaskExecutor;

    public BlogAgentController(BlogAgentService service,
                               @Qualifier("blogAgentTaskExecutor") TaskExecutor blogAgentTaskExecutor) {
        this.service = service;
        this.blogAgentTaskExecutor = blogAgentTaskExecutor;
    }

    @PostMapping
    public Result<BlogAgentTask> create(@Valid @RequestBody BlogAgentTaskCreateRequest request) {
        return Result.success(service.create(UserContext.getUserId(), request));
    }

    @GetMapping("/{id}")
    public Result<BlogAgentTaskVO> get(@PathVariable Long id) {
        return Result.success(service.get(id, UserContext.getUserId()));
    }

    @GetMapping
    public Result<List<BlogAgentSessionVO>> list() {
        return Result.success(service.listSessions(UserContext.getUserId()));
    }

    @PostMapping("/{id}/run")
    public Result<BlogAgentTaskVO> run(@PathVariable Long id) {
        return Result.success(service.run(id, UserContext.getUserId()));
    }

    @PostMapping("/{id}/publish")
    public Result<BlogPostVO> publish(@PathVariable Long id) {
        return Result.success(service.publish(id, UserContext.getUserId()));
    }

    @PostMapping(value = "/{id}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                BlogAgentTaskVO task = service.runStream(id, userId,
                        (event, data) -> send(emitter, event, data));
                emitter.send(SseEmitter.event().name("complete").data(task));
                emitter.complete();
            } catch (Exception e) {
                send(emitter, "error", e.getMessage());
                // The error has been delivered as an SSE event. Completing
                // normally keeps Axios from replacing it with a generic error.
                emitter.complete();
            }
        }, blogAgentTaskExecutor);
        return emitter;
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reviseStream(@PathVariable Long id,
                                   @Valid @RequestBody BlogAgentMessageRequest request) {
        Long userId = UserContext.getUserId();
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                BlogAgentTaskVO task = service.reviseStream(id, userId, request.getContent(),
                        (event, data) -> send(emitter, event, data));
                send(emitter, "complete", task);
                emitter.complete();
            } catch (Exception e) {
                send(emitter, "error", e.getMessage());
                emitter.complete();
            }
        }, blogAgentTaskExecutor);
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ignored) {
            emitter.complete();
        }
    }

}
