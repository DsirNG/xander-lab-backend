package com.xander.lab.identity.api;

import com.xander.lab.core.api.SecurityStateSyncApi;
import com.xander.lab.identity.application.SecurityStateSyncProperties;
import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.infrastructure.AccountSecurityStateMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 供 Gateway 安全平面按实例对账的内网只读 API。
 *
 * <p>这个边界让 Gateway 保持 WebFlux-only：它不依赖 MyBatis，也不直接连接 MySQL。
 * 请求必须携带部署侧注入的内部令牌；API 默认关闭。</p>
 */
@RestController
@RequestMapping("/internal/security-state")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class SecurityStateSyncController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final AccountSecurityStateMapper securityStateMapper;
    private final SecurityStateSyncProperties properties;

    public SecurityStateSyncController(AccountSecurityStateMapper securityStateMapper,
                                       SecurityStateSyncProperties properties) {
        this.securityStateMapper = securityStateMapper;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网同步 API 时不能为空");
        }
    }

    @GetMapping("/snapshot")
    public SecurityStateSyncApi.Snapshot snapshot(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token) {
        authorize(token);
        List<AccountSecurityState> states = securityStateMapper.listAll();
        long watermark = states.stream()
                .map(AccountSecurityState::getChangeSeq)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        return new SecurityStateSyncApi.Snapshot(states.stream()
                .map(SecurityStateSyncController::toEntry)
                .toList(), watermark);
    }

    @GetMapping("/changes")
    public SecurityStateSyncApi.Changes changes(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(defaultValue = "500") int limit) {
        authorize(token);
        if (cursor < 0 || limit < 1 || limit > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法安全状态对账参数");
        }
        return new SecurityStateSyncApi.Changes(securityStateMapper.pullChangesAfter(cursor, limit)
                .stream()
                .map(SecurityStateSyncController::toEntry)
                .toList());
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    private static SecurityStateSyncApi.Entry toEntry(AccountSecurityState state) {
        return new SecurityStateSyncApi.Entry(
                state.getAccountId(),
                state.isDisabled(),
                valueOrZero(state.getSecurityVersion()),
                valueOrZero(state.getChangeSeq()));
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
