package com.xander.lab.integration.api;

/**
 * CSDN 凭证查询端口：<b>这个用户的 CSDN 凭证现在可用吗</b>。
 *
 * <h2>为什么要有这个接口</h2>
 * <p>它由 {@code integration.mcp.csdn.application.CsdnCredentialService} 实现，而调用方在
 * <b>另一个部署单元</b>：{@code automation}（{@code BlogContentPlanService} 与
 * {@code BlogPlanRunService}）在 {@code dinqor-content-automation}，实现类在
 * {@code dinqor-platform-integration}。拆服务之后让 {@code automation} import 那个具体
 * {@code @Service}，就是"一个服务直接调另一个服务的内部类"——依赖方向是反的。</p>
 *
 * <h2>为什么只有一个方法：{@code isAuthorized}</h2>
 * <p>实现类有六个公开方法，但跨域位置只被用到<b>一个</b>。另外五个
 * （{@code isExpired} / {@code markExpired} / {@code requireCookie} / {@code save} /
 * {@code delete}）的调用点<b>全部在 {@code integration} 域内</b>——
 * 授权回调、发布器、定时校验各自用它们。把六个一起端出去，等于让
 * {@code automation} 的适配器被迫实现四个它从不调用的成员，并让"凭证怎么存"变成跨域契约。</p>
 *
 * <p><b>端口宽度 = 调用方适配器被迫实现的成员集。</b>这里刻意只放一个方法，
 * 一半的价值来自<b>没搬</b>的那五个。宽度由 {@code CsdnCredentialPortTest} 用反射钉住。</p>
 *
 * <h2>为什么 CSDN 与掘金是<b>两个</b>端口，而不是一个带 {@code platform} 参数的端口</h2>
 * <p>与 {@link CsdnBlogSyncPort} 的三条理由同源，但这里多了一条<b>反向</b>的考量，
 * 值得单独写清楚 —— 因为这两个端口的<b>方法名与签名逐字相同</b>，合并看起来最"顺"：</p>
 * <ol>
 *   <li><b>域自己就是这么分的。</b>{@code integration} 为两个平台各有一套
 *       {@code *CredentialService} / {@code *PlatformCredential} / {@code *PlatformCredentialMapper}
 *       / 独立的加密密钥（{@code CSDN_CREDENTIAL_ENCRYPTION_KEY} 与
 *       {@code JUEJIN_CREDENTIAL_ENCRYPTION_KEY}）与独立配置。端口跟着域的真实分解走。</li>
 *   <li><b>零适配器。</b>每个端口的方法表与它对应实现类的公开方法逐字相同，
 *       实现类加一句 {@code implements} 就够了 —— 不需要新增一个按平台字符串分派的中间类，
 *       也就没有"分派写错只在运行时才炸"的新缝。</li>
 *   <li><b>合并要造一个域里没有的词汇。</b>一个 {@code platform} 参数需要一个新的跨域枚举，
 *       而两个平台今天唯一的公共词汇就是"用户名 + 加密后的 cookie"这个巧合。
 *       第三个平台出现时那个枚举还要改 —— 契约跟着变。</li>
 * </ol>
 *
 * <p>⚠️ <b>代价：这两个端口挡不住"接收者写反"。</b>{@link CsdnBlogSyncPort} 与
 * {@link JuejinBlogSyncPort} 靠参数表不同（掘金多收分类与标签 id）把这种错误挡在<b>编译期</b>；
 * 这两个端口的方法名与签名完全相同，{@code csdnPort.isAuthorized(userId)} 写成
 * {@code juejinPort.isAuthorized(userId)} <b>编译得过、指标不动、门禁全绿</b>，
 * 症状是"CSDN 发布前检查了掘金的授权"。所以这个错误只能由<b>行为</b>守：
 * {@code BlogPlanRunServiceTest} 与 {@code BlogContentPlanServiceTest} 各有一条
 * 用<b>不对称桩</b>（一个平台授权、另一个不授权）的用例。两个 mock 都返回同一个值的测试
 * <b>抓不到</b>它。</p>
 *
 * <h2>失败语义（跨进程后仍然成立，实现必须遵守）</h2>
 * <ul>
 *   <li><b>未授权返回 {@code false}，不抛。</b>调用方拿这个布尔值去决定"跳过同步并记
 *       {@code AUTH_REQUIRED}"，而不是让整次发布失败。这一点与同域的
 *       {@code requireCookie} 恰好相反 —— 那个方法未授权时抛 {@code IllegalStateException}，
 *       因为它要的是一份能用的凭证，拿不到就没法继续。跨进程后这个区别必须保留：
 *       把 {@code isAuthorized} 改成"未授权即抛"，会让一次"用户还没连 CSDN"的正常跳过
 *       变成 run 失败。</li>
 *   <li>纯查询：<b>不联系外部平台</b>、不消耗平台限流额度、不修改任何行。</li>
 *   <li>⚠️ <b>"不抛"说的是"未授权"，不是"永远不会抛"。</b>查询机制本身失败
 *       （库不可用；拆服务后提供方不可达）仍然抛运行时异常 —— 契约不点名具体类型，
 *       因为契约不许依赖框架类型。<b>调用方按它在<b>这条路径里的语义角色</b>
 *       决定怎么办：</b>只拿它做展示补充的（{@code BlogContentPlanVO} 的
 *       {@code csdnAuthorized}）应当降级为 {@code false} 并留下可观测信号，
 *       而不是让整个请求失败（{@code BlogContentPlanService#credentialAuthorizedOrFalse}）；
 *       若它被用来<b>决定是否允许操作</b>，则必须 fail-closed。
 *       判据是语义角色，不是"它叫凭证"。</li>
 * </ul>
 *
 * <h2>调用方身份</h2>
 * <p>方法显式收 {@code userId} 作为唯一参数，且必须是<b>参数名</b> {@code userId}。
 * 跨进程后提供方读不到调用方的 {@code ThreadLocal}，而 {@code userId} 是凭证归属的唯一依据。</p>
 *
 * <p>⚠️ 这里的 {@code userId} 是<b>凭证属主</b>，<b>不一定等于当前登录用户</b>：
 * {@code BlogPlanRunService} 传的是<b>计划属主</b>（{@code plan.getUserId()}），
 * 而定时任务触发时根本没有登录用户。调用方负责先证明"这个属主是它该管的"，
 * 本端口只回答"这个属主的凭证可用吗"。</p>
 */
public interface CsdnCredentialPort {

    /**
     * 这个用户的 CSDN 凭证是否处于可用状态。
     *
     * <p>"可用"的实现口径：存在一行凭证、状态是 {@code ACTIVE}、且加密的 cookie 与
     * CSDN 用户名都非空。会话被平台判过期（状态 {@code EXPIRED}）时返回 {@code false}，
     * 于是调用方会记 {@code AUTH_REQUIRED} 并提示用户重新授权。</p>
     *
     * @param userId 凭证属主，不一定是当前登录用户
     * @return 未授权时为 {@code false}。<b>未授权不抛</b>；查询机制本身失败时抛运行时异常
     *         （见类注释「失败语义」的最后一条：调用方按语义角色决定降级还是 fail-closed）
     */
    boolean isAuthorized(Long userId);
}
