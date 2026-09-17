package com.xander.lab.common;

import com.xander.lab.common.security.SecurityPlaneReadiness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存活探针与就绪探针。
 *
 * <h3>两个探针为什么要分开（V11 §27.1 第 9 步）</h3>
 * <p>§27.1 要求实例在安全平面就绪之前"不得进入服务发现 / 负载均衡"。这条约束只能由
 * 负载均衡侧的就绪探针落地，所以必须有一个"未就绪即 503"的端点。</p>
 * <p>但不能把 {@link #health()} 直接改成那个端点：它是<b>存活</b>探针，
 * {@code docker-compose.yml} 的 healthcheck 正在用它，而存活探针失败会触发容器重启。
 * 若让它在启动窗口里返回 503，一次正常的启动就会被判为不健康并反复重启。
 * 因此：</p>
 * <ul>
 *   <li>{@code GET /health} —— 存活。语义与状态码<b>不变</b>（Redis 可达即 200），
 *       只多报一个 {@code securityPlane} 字段供观测。</li>
 *   <li>{@code GET /health/ready} —— 就绪。安全平面 {@code READY} <b>且</b> Redis 可达
 *       才 200，否则 503。部署侧的就绪探针应指向这里。</li>
 * </ul>
 *
 * <p>⚠️ 两个探针都复核 Redis 是刻意的冗余：部署平台可能只配其中一个，任一探针单独
 * 存在时都必须是充分的路由信号。</p>
 */
@RestController
public class HealthController {

    private final RedisHealthProbe redisHealthProbe;
    private final SecurityPlaneReadiness securityPlaneReadiness;

    public HealthController(RedisHealthProbe redisHealthProbe,
                            SecurityPlaneReadiness securityPlaneReadiness) {
        this.redisHealthProbe = redisHealthProbe;
        this.securityPlaneReadiness = securityPlaneReadiness;
    }

    /**
     * 存活探针。返回 200 当且仅当能成功 ping 通 Redis。
     *
     * <p>刻意<b>不</b>把安全平面就绪与否反映到状态码上 —— 见类注释。</p>
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("message", "DinQorAI Backend is running");
        status.put("timestamp", System.currentTimeMillis());
        status.put("securityPlane", securityPlaneReadiness.state());
        try {
            redisHealthProbe.ping();
            status.put("redis", "UP");
            return ResponseEntity.ok(status);
        } catch (RuntimeException ex) {
            status.put("status", "DOWN");
            status.put("redis", "DOWN");
            status.put("message", "Redis dependency is unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(status);
        }
    }

    /**
     * 就绪探针：本实例是否可以接流量（V11 §27.1 第 9 步）。
     *
     * <p>安全平面 {@code READY} 之前一律 503，即使进程活着、端口已监听。这是
     * {@code SecurityStateBootstrap} 的 fail-fast 之外<b>唯一</b>能拦住"先接流量再慢慢填
     * deny cache"的机制 —— 端口在容器 refresh 期间就已经开始监听，bootstrap 跑完之前
     * TCP 连接是能建起来的。</p>
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("timestamp", System.currentTimeMillis());
        status.put("securityPlane", securityPlaneReadiness.state());

        if (!securityPlaneReadiness.isReady()) {
            String failure = securityPlaneReadiness.failureReason();
            if (failure != null) {
                // 区分"还在启动中"（无原因）与"启动失败"（有原因），运维据此决定是否介入。
                status.put("securityPlaneFailure", failure);
            }
            status.put("status", "DOWN");
            status.put("message", "安全平面尚未就绪：本地 deny cache 建立完成前不得接流量（V11 §27.1）");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(status);
        }

        status.put("securityPlaneReadyAt", securityPlaneReadiness.readyAtMillis());
        try {
            redisHealthProbe.ping();
            status.put("redis", "UP");
            status.put("status", "UP");
            status.put("message", "Security plane is READY");
            return ResponseEntity.ok(status);
        } catch (RuntimeException ex) {
            status.put("status", "DOWN");
            status.put("redis", "DOWN");
            status.put("message", "Redis dependency is unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(status);
        }
    }
}
