package com.xander.lab.identity.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.xander.lab.common.Constants;
import com.xander.lab.common.PageData;
import com.xander.lab.identity.api.UserAdminView;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    static {
        // 让 LambdaUpdateWrapper 能解析 sys_user 的元数据，否则纯单测里 MyBatis-Plus 拿不到 lambda 缓存。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations setOperations;

    private UserAdminService service;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userMapper, redisTemplate);
    }

    private User user(Long id, String role, int status) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname("昵称" + id);
        user.setEmail("u" + id + "@example.com");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    @Test
    void listUsersClampsPaginationAndPassesKeyword() {
        Page<User> page = new Page<>(1, 2, 1);
        page.setRecords(List.of(user(2L, "USER", 1), user(1L, "USER", 0)));
        when(userMapper.selectPage(any(), any())).thenReturn(page);

        PageData<UserAdminView> result = service.listUsers("  nick  ", 0, 999);

        assertThat(result.getCurrent()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).username()).isEqualTo("u2");
        assertThat(result.getRecords().get(1).status()).isZero();
    }

    @Test
    void banRevokesEveryActiveSessionToken() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "USER", 1));
        when(userMapper.update(any(), any())).thenReturn(1);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of("token-a", "token-b"));
        User banned = user(7L, "USER", 0);
        when(userMapper.selectById(7L)).thenReturn(banned);

        UserAdminView view = service.setUserStatus(1L, 7L, 0);

        assertThat(view.status()).isZero();
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, times(3)).delete(keys.capture());
        assertThat(keys.getAllValues()).contains(
                Constants.REDIS_TOKEN_PREFIX + "token-a",
                Constants.REDIS_TOKEN_PREFIX + "token-b",
                Constants.REDIS_USER_TOKENS_PREFIX + 7L);
    }

    @Test
    void banWithNoSessionsStillRemovesTheSetKey() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "USER", 1));
        when(userMapper.update(any(), any())).thenReturn(1);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of());
        when(userMapper.selectById(7L)).thenReturn(user(7L, "USER", 0));

        service.setUserStatus(1L, 7L, 0);

        verify(redisTemplate).delete(Constants.REDIS_USER_TOKENS_PREFIX + 7L);
    }

    @Test
    void unbanWhenAlreadyEnabledSkipsSessionRevocation() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "USER", 1));
        when(userMapper.update(any(), any())).thenReturn(0);

        UserAdminView view = service.setUserStatus(1L, 7L, 1);

        assertThat(view.status()).isEqualTo(1);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void rejectBanningYourself() {
        assertThatThrownBy(() -> service.setUserStatus(9L, 9L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自己的账号");
    }

    @Test
    void rejectInvalidStatusValue() {
        assertThatThrownBy(() -> service.setUserStatus(1L, 7L, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0（封禁）或 1（解封）");
    }

    @Test
    void rejectMissingTargetUser() {
        when(userMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.setUserStatus(1L, 404L, 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void rejectBanningAnAdminAccount() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "ADMIN", 1));

        assertThatThrownBy(() -> service.setUserStatus(1L, 2L, 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(403);
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void banIsIdempotentWhenUserIsAlreadyBanned() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "USER", 0));
        when(userMapper.update(any(), any())).thenReturn(0);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of());

        UserAdminView view = service.setUserStatus(1L, 7L, 0);

        assertThat(view.status()).isZero();
        verify(redisTemplate).delete(anyString());
    }
}