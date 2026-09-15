package com.xander.lab.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.common.Constants;
import com.xander.lab.common.PageData;
import com.xander.lab.identity.api.UserAdminView;
import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 管理台用户服务：用户分页查询与封禁/解封。
 *
 * <p>封禁会立即撤销该用户全部已签发 token（删除 Redis 中的活跃 token 集合与
 * token 记录），因此封禁即刻生效，无需等待 token 自然过期；解封只改状态。
 * identity 拥有用户与会话，其它模块（如 admin）只能通过本服务操作，不得直接
 * 修改 sys_user 或登录 Redis 键。</p>
 *
 * <h3>双写：遗留 Redis 撤销 + 新安全平面（V9 §2.3 能力守恒）</h3>
 * <p>当前鉴权仍然以 Redis 为准（{@code AuthInterceptor} 查 {@code login:token:<jwt>}），
 * 所以封禁必须<b>继续</b>走 {@link #revokeSessions} —— 换成新 SOT 就删掉它，
 * 会让封禁在 Wave 2 切换之前完全失效。同时把安全状态写进
 * {@code account_security_state} 并发一条事务性事件，让长连接封禁
 * （§50 / §51）与 Gateway 侧的本地 deny cache 提前就位。</p>
 *
 * <p>两条路径都幂等，重复执行不会互相干扰；Wave 2 切换后遗留那条再退场
 * （已登记在 {@code contract-changes.yaml} 的 {@code legacy-redis-user-tokens}）。</p>
 *
 * <h3>事务边界（V9 §26）</h3>
 * <p>{@link #setUserStatus} 与 {@link AccountSecurityStateService#applyState} 在同一个
 * 事务里：SOT 写入、Refresh Session 撤销、Outbox 事件三者要么全成、要么全不成。
 * 唯一不在事务里的是 {@link #revokeSessions} —— Redis 没有回滚。这是<b>故意保留</b>的：
 * 它只可能"多撤销"，不可能"少撤销"，因此最坏结果是用户被迫重新登录一次，
 * 而不会出现"封禁没生效"。方向上的不对称是安全的，且它在 Wave 2 就消失了。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 64;

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final AccountSecurityStateService accountSecurityStateService;

    /**
     * 分页查询用户（可按用户名/昵称/邮箱模糊过滤），按创建时间倒序。
     */
    @Transactional(readOnly = true)
    public PageData<UserAdminView> listUsers(String keyword, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        String trimmed = keyword == null ? null : keyword.trim();
        if (StringUtils.hasText(trimmed)) {
            String safe = trimmed.length() > MAX_KEYWORD_LENGTH
                    ? trimmed.substring(0, MAX_KEYWORD_LENGTH) : trimmed;
            wrapper.and(q -> q.like(User::getUsername, safe)
                    .or().like(User::getNickname, safe)
                    .or().like(User::getEmail, safe));
        }
        wrapper.orderByDesc(User::getCreatedAt);
        Page<User> result = userMapper.selectPage(new Page<>(current, pageSize), wrapper);

        PageData<UserAdminView> pageData = new PageData<>();
        pageData.setRecords(result.getRecords().stream().map(this::toView).toList());
        pageData.setTotal(result.getTotal());
        pageData.setCurrent(result.getCurrent());
        pageData.setPages(result.getPages());
        pageData.setSize(result.getSize());
        pageData.setHasMore(result.getCurrent() < result.getPages());
        return pageData;
    }

    /**
     * 封禁（status=0）或解封（status=1）用户。
     * 禁止操作自己和其他管理员账号；封禁时同步撤销该用户全部会话。
     *
     * @param operatorId 当前管理员用户 ID（防止自封）
     */
    @Transactional
    public UserAdminView setUserStatus(Long operatorId, Long targetId, int status) {
        if (operatorId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        if (operatorId.equals(targetId)) {
            throw new IllegalArgumentException("不能修改自己的账号状态");
        }
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("状态只能是 0（封禁）或 1（解封）");
        }
        User target = userMapper.selectById(targetId);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        if ("ADMIN".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能封禁管理员账号");
        }

        boolean disabled = status == 0;

        // ① 业务状态（sys_user.status）：条件更新，仅当状态确实变化时才写库，
        //    天然幂等；0 行影响说明已处于目标状态。
        int rows = userMapper.update(new User(), new LambdaUpdateWrapper<User>()
                .eq(User::getId, targetId)
                .ne(User::getStatus, status)
                .set(User::getStatus, status));

        // ② 遗留 Redis 撤销。鉴权在 Wave 2 切换前仍以 Redis 为准，所以这一条
        //    绝不能删 —— 删掉会让封禁在切换前完全失效（§2.3 能力守恒）。
        int revoked = 0;
        if (disabled) {
            revoked = revokeSessions(targetId);
        }

        // ③ 新安全平面：SOT + 事务性事件（§26 / §27 / §48）。
        //    注意这里【无条件】写，不跟随 ① 的 rows：① 只保证 sys_user 与目标一致，
        //    而 account_security_state 可能因为历史数据、上一次崩溃、手工改库而与它
        //    不一致。每次管理员操作都顺手收敛一次，是这张表最便宜的自愈机会。
        AccountSecurityState state = accountSecurityStateService.applyState(
                targetId, disabled, auditReason(operatorId, disabled));

        log.info("[Admin] {} 用户 {} operator={} sys_user_changed={} legacy_revoked={} securityVersion={}",
                disabled ? "封禁" : "解封", targetId, operatorId, rows > 0, revoked,
                state.getSecurityVersion());
        return toView(userMapper.selectById(targetId));
    }

    /**
     * 构造写入 SOT 与安全事件的审计说明。
     *
     * <p>不带管理员自由文本：本接口的请求体只有 {@code status}，凭空加一个可写字段
     * 属于新增产品能力，要走 §11 Contract 流程。当前先把"谁做的、做了什么"记全，
     * 已经能回答审计最常问的那个问题。</p>
     */
    private String auditReason(Long operatorId, boolean disabled) {
        return (disabled ? "admin-ban" : "admin-unban") + " operator=" + operatorId;
    }

    /**
     * 撤销用户全部活跃会话：删除每个 token 的登录记录及其所属用户 token 集合。
     * 返回撤销的 token 数量；删集合键在最后执行，避免并发写回残留。
     */
    private int revokeSessions(Long userId) {
        String setKey = Constants.REDIS_USER_TOKENS_PREFIX + userId;
        Set<String> tokens = redisTemplate.opsForSet().members(setKey);
        if (tokens == null || tokens.isEmpty()) {
            redisTemplate.delete(setKey);
            return 0;
        }
        for (String token : tokens) {
            try {
                redisTemplate.delete(Constants.REDIS_TOKEN_PREFIX + token);
            } catch (Exception e) {
                log.warn("[Admin] 撤销 token 失败 userId={}: {}", userId, e.getMessage());
            }
        }
        redisTemplate.delete(setKey);
        return tokens.size();
    }

    private UserAdminView toView(User user) {
        return new UserAdminView(user.getId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
