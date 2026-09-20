package com.xander.lab.integration.api;

/**
 * CSDN 站外同步端口：把一篇<b>已存在的本地文章</b>推到用户自己的 CSDN 账号，以及查它同步成功没有。
 *
 * <h2>为什么要有这个接口</h2>
 * <p>它由 {@code integration.mcp.csdn.application.CsdnBlogSyncService} 实现，而调用方在
 * <b>另一个部署单元</b>：{@code agent}（{@code PublishPostTool}）在 {@code dinqor-agent}，
 * 实现类在 {@code dinqor-platform-integration}。拆服务之后让 {@code agent} import 那个具体
 * {@code @Service}，就是"一个服务直接调另一个服务的内部类"——依赖方向是反的。
 * 端口立起来之后，将来由 HTTP 客户端实现还是同进程 bean 实现，只是装配问题。</p>
 *
 * <h2>为什么在 {@code integration-api} 而不是 {@code agent-api}</h2>
 * <p>因为它不是 agent 的能力面。同一个能力有<b>两个</b>跨域消费方：{@code agent} 与
 * {@code automation}（{@code BlogPlanRunService}，走 {@link JuejinBlogSyncPort}）。
 * 契约按<b>能力</b>命名、按<b>提供方</b>归位，不按第一个调用方归位 ——
 * 否则第二个消费方接入时要么依赖 agent 的契约、要么再抄一份。
 * 这与 {@code core-api} 的 {@code UserLookupPort} 是同一条判据
 * （消费方是 publishing / components，端口仍在 {@code core-api}）。</p>
 *
 * <h2>为什么 CSDN 与掘金是<b>两个</b>端口，而不是一个带 {@code platform} 参数的端口</h2>
 * <p>这一条值得写清楚，因为第一直觉是后者。三个理由：</p>
 * <ol>
 *   <li><b>参数表不同，合并就要么造假要么变宽。</b>{@link JuejinBlogSyncPort#sync} 多收
 *       {@code juejinCategoryId} / {@code juejinTagIds} —— 掘金要求自己的分类与标签 id，
 *       而 CSDN 没有这两个概念。合并成一个端口，CSDN 那条路就必须收下两个它永远不用的参数
 *       （端口变宽），或者端口只带交集、把掘金的能力砍掉（能力静默降级）。</li>
 *   <li><b>域自己就是这么分的。</b>{@code integration} 域为两个平台各有一套
 *       {@code *CredentialService} / {@code *BlogSyncService} / {@code *BlogSyncController} /
 *       {@code *McpApplicationService}，两套凭证存储、两套限流、两套 MCP 应用服务。
 *       端口跟着域的真实分解走，而不是把域合并成它没有的样子。</li>
 *   <li><b>零适配器。</b>每个端口的方法表与它对应实现类的公开方法<b>逐字相同</b>，
 *       实现类加一句 {@code implements} 就够了 —— 不需要新增一个按平台字符串分派的
 *       中间类，也就没有"分派写错只在运行时才炸"的新缝。</li>
 * </ol>
 * <p>代价是两个几乎同形的接口，共享同一个 {@link BlogSyncResult}。</p>
 *
 * <h2>⚠️ {@link #findSucceeded(Long, Long)} 与掘金那个<b>签名逐字相同</b></h2>
 * <p>所以同时持有两个端口的调用方里，{@code csdnPort.findSucceeded(u, p)} 写成
 * {@code juejinPort.findSucceeded(u, p)} <b>编译通过</b>、耦合指标一动不动、门禁全绿。
 * {@link #sync} 没有这个问题：掘金多收两个参数，写反编译不过 ——
 * 那是"参数表恰好不同"的副产品，不是设计出来的保护。</p>
 * <p>今天的调用方<b>恰好一个</b>：{@code PublishPostTool}（同时持有两个端口），
 * 它的不对称桩用例在 {@code PublishPostToolTest}。再出现第二个这样的类会被
 * {@code DualSyncPortConsumerTest} 拦下；"哪些方法可互换"由
 * {@code BlogSyncPortInterchangeabilityTest} 钉住。</p>
 *
 * <h2>失败语义（跨进程后仍然成立，实现必须遵守）</h2>
 * <ul>
 *   <li>{@link #sync} 失败时<b>抛异常</b>，不返回"失败的结果对象"——{@link BlogSyncResult}
 *       里没有表达失败的字段，所以"没抛"就等于"成功了"。</li>
 *   <li>文章不存在、或已被软删除（{@code status == -1}）→ {@link IllegalArgumentException}。
 *       调用方不该把它当平台故障，重试没有意义。</li>
 *   <li>{@link #findSucceeded} 是纯查询：没有成功记录时返回 {@code null}，
 *       且<b>不会</b>联系外部平台、不消耗限流额度。它给"对账"用 ——
 *       用户说"我发过了"，调用方据此确认而不重新发一遍。</li>
 *   <li>{@code postId} 为 {@code null} 时 {@link #findSucceeded} 返回 {@code null}（不抛）。</li>
 * </ul>
 *
 * <h2>调用方身份</h2>
 * <p>两个方法都<b>显式</b>收 {@code userId} 作为第一个参数，且必须是<b>参数名</b>
 * {@code userId}。跨进程后提供方读不到调用方的 {@code ThreadLocal}，
 * 而 {@code userId} 又是归属判定的唯一依据（文章属主、CSDN 凭证属主）——
 * 少了它，同步会用"当前登录用户"去发别人的文章。</p>
 *
 * <p><b>⚠️ 端口宽度没有任何东西在守。</b>加一个 {@code default} 方法不会编译报错、
 * 不会动任何耦合指标、门禁也不查。方法个数与完整签名由 {@code CsdnBlogSyncPortTest}
 * 用反射钉住。</p>
 */
public interface CsdnBlogSyncPort {

    /**
     * 把一篇本地文章同步到该用户的 CSDN 账号。
     *
     * <p>幂等由内容域保证：同 {@code postId} 再次调用不会重复打扰平台。</p>
     *
     * @param userId 文章属主，也是 CSDN 凭证属主
     * @param postId 本地文章 id
     * @return 平台标识、平台侧文章 id 与可访问 URL
     * @throws IllegalArgumentException 文章不存在或已被软删除
     */
    BlogSyncResult sync(Long userId, Long postId);

    /**
     * 查这篇文章是否已经成功同步过，<b>不联系外部平台</b>。
     *
     * @param userId 文章属主
     * @param postId 本地文章 id；{@code null} 时返回 {@code null}
     * @return 没有成功记录时为 {@code null}
     */
    BlogSyncResult findSucceeded(Long userId, Long postId);
}
