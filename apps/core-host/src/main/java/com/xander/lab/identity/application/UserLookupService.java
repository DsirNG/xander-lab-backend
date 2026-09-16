package com.xander.lab.identity.application;

import com.xander.lab.core.api.UserLookupPort;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * identity 对 {@link UserLookupPort} 的实现。
 *
 * <p>存在的唯一目的：让"用户表怎么读"留在 identity 域内。在此之前，
 * {@code publishing} 与 {@code components} 各自注入 {@code UserMapper} 去查昵称，
 * 于是 {@code sys_user} 的表结构事实上被三个域共享。现在读法只有这一份，
 * 换列名 / 加缓存 / 改判据都只改这里。</p>
 *
 * <p>本类不参与鉴权，也不返回密码、openid、角色等字段 ——
 * 端口只承诺"展示名"这一个值，实现就不要多给。</p>
 */
@Service
@RequiredArgsConstructor
public class UserLookupService implements UserLookupPort {

    private final UserMapper userMapper;

    @Override
    public String displayName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return null;
    }
}
