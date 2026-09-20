package com.xander.lab.core.api;

/**
 * 用户展示名查询端口。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>在这之前有两个域把 {@code identity.infrastructure.UserMapper}（一个 MyBatis
 * mapper）和 {@code identity.domain.User}（{@code sys_user} 的持久化实体）直接注入自己的
 * 服务，只为了取一个展示名：{@code publishing.application.BlogService#resolveAuthor} 与
 * {@code components.application.ComponentService#shareComponent}。两处都是
 * {@code userMapper.selectById(userId)} 然后读昵称/用户名。</p>
 *
 * <p>这正是 identity 自己的 {@code package-info} 明文禁止的事
 * （"Feature modules ... must not parse tokens or query the user table directly"）。
 * 而且 {@code publishing} 属于 dinqor-content-automation、{@code identity} 属于
 * dinqor-core-host —— 拆分之后那就是"一个服务直接连另一个服务的库表"，
 * 表结构一改两边一起坏，而编译期不会有任何提示。</p>
 *
 * <h2>为什么只有这一个方法</h2>
 *
 * <p>端口只覆盖**当前真实存在的跨域需求**。两个调用方要的都只是一个展示名，
 * 所以这里只有一个方法。不要因为"以后可能要看头像 / 邮箱"就提前加字段 ——
 * 端口每多一个方法，就多一份被当成通用用户查询接口滥用的空间，
 * 而那正是本端口要消除的耦合。真出现新需求时再加，加的时候它同样有据可查。</p>
 *
 * <h2>为什么返回 {@code null} 而不是兜底文案</h2>
 *
 * <p>两个调用方的兜底**不一样**：博客用 {@code "User " + userId}，
 * 组件库用 {@code "匿名用户" + userId}。兜底是调用方的显示策略，不是 identity 的数据，
 * 所以端口只回答"这个用户的展示名是什么"，答不上来返回 {@code null}，
 * 由调用方自己决定怎么显示。把兜底塞进端口会逼两个域接受同一种文案。</p>
 */
public interface UserLookupPort {

    /**
     * 用户的展示名：优先昵称，其次用户名。
     *
     * <p>规则：昵称为非空白 → 昵称；否则用户名为非空白 → 用户名；两者都空白、
     * 或用户不存在、或 {@code userId} 为 {@code null} → {@code null}。</p>
     *
     * <p>⚠️ 判据是"非空白"而不是"非 null"：{@code sys_user.nickname} 允许空串，
     * 而把空串当作者名显示出来是一个缺陷。{@code components.ComponentService}
     * 在改用本端口之前的行为是 {@code nickname != null ? nickname : username}，
     * 所以昵称为空串时它会显示**空**作者名 —— 这里统一改成"空串往下走"。</p>
     *
     * @param userId 用户 id，可为 {@code null}
     * @return 展示名；无法确定时返回 {@code null}，由调用方自行兜底
     */
    String displayName(Long userId);
}
