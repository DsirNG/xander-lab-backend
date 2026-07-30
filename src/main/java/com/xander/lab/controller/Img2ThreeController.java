package com.xander.lab.controller;

import com.xander.lab.common.Result;
import com.xander.lab.common.UserContext;
import com.xander.lab.dto.img2three.Img2ThreeTaskSummaryVO;
import com.xander.lab.dto.img2three.Img2ThreeTaskVO;
import com.xander.lab.entity.Img2ThreeTask;
import com.xander.lab.service.Img2ThreeService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/img2three/tasks")
public class Img2ThreeController {
    private static final Logger log = LoggerFactory.getLogger(Img2ThreeController.class);
    private final Img2ThreeService service;
    private final TaskExecutor img2ThreeTaskExecutor;

    public Img2ThreeController(Img2ThreeService service,
                               @Qualifier("img2ThreeTaskExecutor") TaskExecutor img2ThreeTaskExecutor) {
        this.service = service;
        this.img2ThreeTaskExecutor = img2ThreeTaskExecutor;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Img2ThreeTask> create(@RequestPart("file") MultipartFile file) {
        try {
            return Result.success(service.create(UserContext.getUserId(), file));
        } catch (IllegalArgumentException e) {
            return mapClientError(e);
        }
    }

    @GetMapping
    public Result<List<Img2ThreeTaskSummaryVO>> list() {
        try {
            return Result.success(service.list(UserContext.getUserId()));
        } catch (IllegalArgumentException e) {
            return mapClientError(e);
        }
    }

    @GetMapping("/{id}")
    public Result<Img2ThreeTaskVO> get(@PathVariable Long id) {
        try {
            return Result.success(service.get(id, UserContext.getUserId()));
        } catch (IllegalArgumentException e) {
            return mapClientError(e);
        }
    }

    @PostMapping(value = "/{id}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable Long id, HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        // The reconstruction is durable and must continue after a browser, proxy, or SSE connection closes.
        // Emitter callbacks are intentionally best-effort notifications only.
        emitter.onCompletion(() -> log.debug("img2three SSE subscriber closed for task {}", id));
        emitter.onTimeout(() -> log.debug("img2three SSE subscriber timed out for task {}", id));
        CompletableFuture.runAsync(() -> {
            try {
                Img2ThreeTaskVO task = service.runStream(id, userId,
                        (event, data) -> send(emitter, event, data));
                send(emitter, "complete", task);
                emitter.complete();
            } catch (Exception e) {
                send(emitter, "error", e.getMessage() == null ? "重建失败" : e.getMessage());
                emitter.complete();
            }
        }, img2ThreeTaskExecutor);
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException ignored) {
            // A disconnected SSE subscriber must never cancel the durable background task.
            log.debug("img2three SSE event {} was dropped because the subscriber is closed", event);
        }
    }

    private <T> Result<T> mapClientError(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "请求无效" : e.getMessage();
        if (message.contains("未登录")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
        }
        if (message.contains("不存在")) {
            return Result.notFound(message);
        }
        return Result.badRequest(message);
    }
}
