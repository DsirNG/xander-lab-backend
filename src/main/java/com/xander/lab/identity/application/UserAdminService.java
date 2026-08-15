package com.xander.lab.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.common.Constants;
import com.xander.lab.common.PageData;
import com.xander.lab.identity.api.UserAdminView;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 64;

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

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

        // 条件更新：仅当状态确实变化时才写库，天然幂等；0 行影响说明已处于目标状态。
        int rows = userMapper.update(new User(), new LambdaUpdateWrapper<User>()
                .eq(User::getId, targetId)
                .ne(User::getStatus, status)
                .set(User::getStatus, status)
                .set(User::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0 && status == 1) {
            // 解封时不需要撤销会话；直接返回库中最新状态。
            return toView(userMapper.selectById(targetId));
        }

        if (status == 0) {
            int revoked = revokeSessions(targetId);
            log.info("[Admin] 封禁用户 {} (revoked {} sessions)", targetId, revoked);
        }
        User updated = userMapper.selectById(targetId);
        return toView(updated);
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