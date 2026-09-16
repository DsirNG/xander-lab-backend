package com.xander.lab.identity.application;

import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link UserLookupService} 的展示名判据。
 *
 * <p>这几条不是形式覆盖。改端口之前，两个调用方各自实现了一份判据，而且**不一致**：
 * {@code BlogService} 用"非空白"，{@code ComponentService} 用"非 null"。
 * 后者意味着 {@code nickname = ""} 时组件库会把**空字符串**当作者名显示出来。
 * 现在判据只有这一份，所以它必须被钉住 —— 否则下一次有人"顺手简化"成
 * {@code nickname != null ? nickname : username}，那个缺陷就悄悄回来了。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserLookupServiceTest {

    @Mock
    private UserMapper userMapper;

    private UserLookupService service;

    @BeforeEach
    void setUp() {
        service = new UserLookupService(userMapper);
    }

    private static User user(String nickname, String username) {
        User u = new User();
        u.setNickname(nickname);
        u.setUsername(username);
        return u;
    }

    @Test
    void prefersTheNickname() {
        when(userMapper.selectById(7L)).thenReturn(user("小明", "xiaoming"));

        assertThat(service.displayName(7L)).isEqualTo("小明");
    }

    @Test
    void fallsBackToTheUsernameWhenTheNicknameIsBlank() {
        when(userMapper.selectById(7L)).thenReturn(user("   ", "xiaoming"));

        assertThat(service.displayName(7L)).isEqualTo("xiaoming");
    }

    /** 空串昵称不是"有昵称"。这是与旧 {@code ComponentService} 行为的唯一差别。 */
    @Test
    void treatsAnEmptyNicknameAsAbsentRatherThanAsAName() {
        when(userMapper.selectById(7L)).thenReturn(user("", "xiaoming"));

        assertThat(service.displayName(7L)).isEqualTo("xiaoming");
    }

    @Test
    void returnsNullWhenBothFieldsAreBlank() {
        when(userMapper.selectById(7L)).thenReturn(user("", "  "));

        assertThat(service.displayName(7L)).isNull();
    }

    @Test
    void returnsNullWhenTheUserDoesNotExist() {
        when(userMapper.selectById(7L)).thenReturn(null);

        assertThat(service.displayName(7L)).isNull();
    }

    @Test
    void returnsNullForANullUserIdWithoutTouchingTheMapper() {
        assertThat(service.displayName(null)).isNull();

        verifyNoInteractions(userMapper);
    }
}
