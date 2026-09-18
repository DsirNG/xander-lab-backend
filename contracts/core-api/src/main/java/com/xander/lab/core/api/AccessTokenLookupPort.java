package com.xander.lab.core.api;

/**
 * 访问令牌身份查询端口（V9 §18 / §91）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@code agent.api.AgentStreamAuthHandshakeInterceptor} 要给 {@code /ws/agent} 的握手做鉴权，
 * 而握手路径不在 {@code /api/**} 下，走不到 {@code identity.security.AuthInterceptor}，
 * 于是它直接注入了 {@code identity.application.AuthService} 并调用
 * {@code getUserIdFromAccessToken(token)}；{@code agent.api.AgentWebSocketConfig} 再把同一个
 * {@code AuthService} 转手传进拦截器。两条 import 就是
 * {@code agent -> identity} 这条跨部署单元边的全部内容。</p>
 *
 * <p>拆分之后 {@code agent} 在 dinqor-agent、{@code identity} 在 dinqor-core-host，
 * 那意味着"智能体服务为了校验一个令牌，要依赖身份服务的实现类"。这正是 identity 自己的
 * {@code package-info} 禁止的事（"Feature modules ... must not parse tokens or query the user
 * table directly"）。</p>
 *
 * <h2>为什么在 core-api 而不是 agent-api</h2>
 *
 * <p>§18 固定了 8 个契约名，里面<b>没有 identity</b>；而 {@code core-api} 是
 * dinqor-core-host 这个部署单元的契约，identity 正好住在那里 ——
 * 与 {@link ModelGateway}、{@link UserLookupPort} 是同一个判据：
 * <b>一个类型属于这里，判据不是"看起来像契约"，而是它的实现跨部署单元。</b></p>
 *
 * <p>另一个更实际的理由：这个能力<b>不止一个消费方</b>。
 * {@code integration.mcp.security.application.McpOAuthService} 今天用的是
 * {@code authService.validateAccessToken(token)}（只问"还有效吗"），
 * 而那与 {@code resolveUserId(token) != null} 是同一个判断 ——
 * 见下面"两个方法其实是一个"那段。若把端口建在 {@code agent-api}，
 * identity 就要为了同一个能力去依赖每一个消费方的契约模块；
 * 建在 {@code core-api} 则 identity 只依赖一个它<b>已经</b>依赖的模块
 * （它已经实现了 {@link UserLookupPort}），且未来任何宿主都能直接用。</p>
 *
 * <h2>为什么只有这一个方法</h2>
 *
 * <p>端口只覆盖<b>当前真实存在的跨域需求</b>：调用方要的就是"这个令牌是谁"。
 * 不要因为"以后可能要看角色 / 会话 id / 过期时间"就提前加方法 ——
 * 端口每多一个方法，就多一份被当成通用认证接口滥用的空间，
 * 而那正是本端口要消除的耦合。{@code AccessTokenLookupPortTest} 把方法个数钉住了。</p>
 *
 * <h2>为什么返回 {@code null} 而不是抛异常 / 返回 {@code Optional}</h2>
 *
 * <p>令牌无效是<b>预期结果</b>而不是异常：握手阶段每天都会收到过期令牌与伪造令牌，
 * 调用方要做的是"拒绝这次握手"，不是"处理一个故障"。所以端口用 {@code null} 表达
 * "这个令牌不构成一个有效身份"，由调用方决定回 401 还是别的。
 * 这与 {@link UserLookupPort#displayName(Long)} 同款：答不上来返回 {@code null}，
 * 兜底策略属于调用方。</p>
 *
 * <p>不用 {@code Optional} 是为了与 {@link UserLookupPort} 保持同一种形状 ——
 * 两个端口都是"查不到就是 null"，调用方读起来只有一种约定。
 * 契约形状一旦定下就是跨服务的（§11），两种风格混用会让每个消费方都要先查文档。</p>
 *
 * <h2>⚠️ 这个端口<b>产出</b> userId，所以它<b>不许</b>收 userId</h2>
 *
 * <p>其他几个端口（{@code UserLookupPort}、{@code NotificationPort}、
 * {@code BlogAgentPort} …）的第一个参数都是调用方身份，守卫用
 * {@code assertFirstParameterIsTheCallerId} 正面断言参数名。本端口是反过来的：
 * 它的整个作用就是<b>从令牌里得出</b>调用方身份，方法里出现一个 {@code userId} 参数
 * 说明有人在让它"校验这个令牌属不属于这个人"，那是另一个能力（且会诱使实现者
 * 把归属校验和令牌校验混成一步）。{@code AccessTokenLookupPortTest} 因此有一条
 * <b>反向</b>守卫：断言端口里没有任何参数叫 {@code userId}。</p>
 */
public interface AccessTokenLookupPort {

    /**
     * 校验访问令牌并返回它所属的用户 id。
     *
     * <p>判据与 HTTP 通道上的鉴权<b>同一套语义</b>（由提供方保证）：
     * 签名有效、未过期、不是 refresh / bind 之类的非访问令牌，
     * 且该令牌仍在会话登记表里（登出会把它移出）。任一条不成立返回 {@code null}。</p>
     *
     * <p>⚠️ 返回 {@code null} 的三种情况对调用方是<b>同一种</b>结果 —— 都表示
     * "这个令牌不构成一个有效身份"。调用方不要试图区分"过期"与"伪造"：
     * 那会泄漏令牌状态，而调用方也做不了不同的事。</p>
     *
     * @param accessToken 访问令牌原文（不含 {@code Bearer } 前缀），可为 {@code null}
     * @return 该令牌所属的用户 id；令牌无效 / 过期 / 已登出 / 参数为 {@code null} 时返回
     *         {@code null}
     */
    Long resolveUserId(String accessToken);
}
