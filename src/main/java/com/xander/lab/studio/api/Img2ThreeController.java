package com.xander.lab.studio.api;

import com.xander.lab.common.Result;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.studio.api.Img2ThreeTaskSummaryVO;
import com.xander.lab.studio.api.Img2ThreeTaskVO;
import com.xander.lab.studio.application.Img2ThreeService;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图片转三维任务的 REST + SSE 门面。
 *
 * <p><b>封禁（V9 §50 / §51 / §52）</b>：本 SSE 用 {@code new SseEmitter(0L)} 永不超时。
 * 注意本端点<b>没有</b>心跳调度器，所以 §28.4 的「心跳兜底」在这里不存在 ——
 * 主要手段只能是每实例 reconciler 的主动断开（手段 A）。这也是 §52 把
 * {@code dinqor-core-host} 列进 Security Local Cache 清单的原因：
 * Wave 2 之后、Wave 3 之前，studio 的这个流仍由 core-host 承载。</p>
 *
 * <p>手段 B（{@link #send} 逐帧 O(1) 校验）依然实现，用于覆盖
 * 「封禁事件已到、reconciler 尚未扫到」的那 1~2 秒窗口。</p>
 */
@RestController
@RequestMapping("/api/img2three/tasks")
public class Img2ThreeController {
    private static final Logger log = LoggerFactory.getLogger(Img2ThreeController.class);
    private final Img2ThreeService service;
    private final TaskExecutor img2ThreeTaskExecutor;
    /** 本实例的已封禁账号集合（V9 §48）。 */
    private final SecurityStateCache securityStateCache;
    /** 本实例的活跃连接注册表（V9 §49）。 */
    private final ConnectionRegistry connectionRegistry;

    public Img2ThreeController(Img2ThreeService service,
                               @Qualifier("img2ThreeTaskExecutor") TaskExecutor img2ThreeTaskExecutor,
                               SecurityStateCache securityStateCache,
                               ConnectionRegistry connectionRegistry) {
        this.service = service;
        this.img2ThreeTaskExecutor = img2ThreeTaskExecutor;
        this.securityStateCache = securityStateCache;
        this.connectionRegistry = connectionRegistry;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Img2ThreeTaskVO> create(@RequestPart("file") MultipartFile file) {
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
        Img2ThreeStream stream = new Img2ThreeStream(id, userId, emitter);
        connectionRegistry.register(stream);
        // The reconstruction is durable and must continue after a browser, proxy, or SSE connection closes.
        // Emitter callbacks are intentionally best-effort notifications only: they unregister the
        // connection handle, but never cancel the background task.
        emitter.onCompletion(stream::markClosed);
        emitter.onTimeout(stream::markClosed);
        emitter.onError(error -> stream.markClosed());
        CompletableFuture.runAsync(() -> {
            try {
                Img2ThreeTaskVO task = service.runStream(id, userId,
                        (event, data) -> send(stream, event, data));
                send(stream, "complete", task);
                emitter.complete();
            } catch (Exception e) {
                send(stream, "error", e.getMessage() == null ? "重建失败" : e.getMessage());
                emitter.complete();
            } finally {
                stream.markClosed();
            }
        }, img2ThreeTaskExecutor);
        return emitter;
    }

    /**
     * 统一推送出口（V9 §50 手段 B）：任何一帧出站前都校验封禁状态。
     *
     * <p>命中封禁时关闭的是<b>推送</b>，不是后台重建任务 —— 任务本身是持久化的，
     * 关掉它会让用户的资产状态与数据库不一致。</p>
     */
    private void send(Img2ThreeStream stream, String event, Object data) {
        if (!stream.isOpen()) return;
        if (securityStateCache.isDisabled(stream.userId)) {
            log.info("[Security] 账号已封禁，终止已建立的 SSE 推送 account={} task={}",
                    stream.userId, stream.taskId);
            stream.close(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
            return;
        }
        try {
            stream.emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException ignored) {
            // A disconnected SSE subscriber must never cancel the durable background task.
            stream.markClosed();
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

    /**
     * 一条 img2three SSE 订阅的连接句柄（V9 §49 / §50）。
     *
     * <p>与其它宿主不同的是：这里的 {@link #close(String)} 只终止推送，
     * 后台重建任务继续跑完并落库。断开连接与取消任务在这个端点上<b>是两件事</b>。</p>
     */
    final class Img2ThreeStream implements ConnectionRegistry.ConnectionHandle {
        private final Long taskId;
        private final Long userId;
        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);

        Img2ThreeStream(Long taskId, Long userId, SseEmitter emitter) {
            this.taskId = taskId;
            this.userId = userId;
            this.emitter = emitter;
        }

        @Override
        public Long accountId() {
            return userId;
        }

        @Override
        public String kind() {
            return ConnectionRegistry.KIND_SSE;
        }

        @Override
        public void close(String reason) {
            if (!open.getAndSet(false)) return;
            connectionRegistry.unregister(this);
            try {
                emitter.completeWithError(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        reason == null ? ConnectionRegistry.REASON_ACCOUNT_DISABLED : reason));
            } catch (IllegalStateException ignored) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignoredAgain) {
                    // The client or container already completed the response.
                }
            }
        }

        /** 连接自然结束（终态、客户端断开、发送失败）时摘除自己。 */
        void markClosed() {
            open.set(false);
            connectionRegistry.unregister(this);
        }

        boolean isOpen() {
            return open.get();
        }
    }
}
