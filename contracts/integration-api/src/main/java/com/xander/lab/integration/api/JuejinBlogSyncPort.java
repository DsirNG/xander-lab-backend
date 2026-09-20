package com.xander.lab.integration.api;

import java.util.List;

/**
 * 掘金站外同步端口：把一篇<b>已存在的本地文章</b>推到用户自己的掘金账号，以及查它同步成功没有。
 *
 * <h2>为什么要有这个接口</h2>
 * <p>它由 {@code integration.mcp.juejin.application.JuejinBlogSyncService} 实现，
 * 而跨域消费方有<b>两个</b>，都在别的部署单元：
 * {@code agent} 的 {@code PublishPostTool}（{@code dinqor-agent}）与
 * {@code automation} 的 {@code BlogPlanRunService}（{@code dinqor-content-automation}）。
 * 两个消费方都在 {@code dinqor-platform-integration} 之外，而实现类在里面 ——
 * 端口立起来之前，它们是直接 import 另一个服务的 {@code @Service}。</p>
 *
 * <p>关于"为什么 CSDN 与掘金是两个端口而不是一个带 {@code platform} 参数的端口"，
 * 理由写在 {@link CsdnBlogSyncPort} 的类注释里（参数表不同、域自己就是两套、零适配器）。
 * 这里只补一条掘金特有的：它的同步<b>真的多收两个参数</b>，
 * 合并端口就必须在 CSDN 那条路上收下它们。</p>
 *
 * <h2>⚠️ {@link #findSucceeded(Long, Long)} 与 CSDN 那个<b>签名逐字相同</b></h2>
 * <p>所以同时持有两个端口的调用方里，把 {@code csdnPort.findSucceeded(u, p)} 写成
 * {@code juejinPort.findSucceeded(u, p)}（或反过来）<b>编译通过</b>、耦合指标一动不动、
 * 门禁全绿。{@link #sync} 没有这个问题 —— 本方法多收两个参数，写反编译不过。</p>
 * <p>今天的调用方<b>恰好一个</b>：{@code PublishPostTool}（同时持有两个端口），
 * 它的不对称桩用例在 {@code PublishPostToolTest}。再出现第二个这样的类会被
 * {@code DualSyncPortConsumerTest} 拦下；"哪些方法可互换"由
 * {@code BlogSyncPortInterchangeabilityTest} 钉住。</p>
 *
 * <h2>⚠️ 这两个参数目前跨域调用方一律传 {@code null}</h2>
 * <p>{@code PublishPostTool} 与 {@code BlogPlanRunService} 传的都是 {@code (null, null)} ——
 * 也就是"用本地文章自己的标签名，由发布器解析成掘金 tag_id"。
 * 但<b>它们仍然在端口上</b>，这不是宽：同域的前端 HTTP 层
 * （{@code JuejinBlogSyncController}）会传真实值，而跨进程之后端口是那条路径唯一能穿过的形状 ——
 * 把参数砍掉就是让"指定掘金分类/标签"这项能力在拆服务时静默消失（禁止静默降级）。
 * 这里没有任何字段是"没人要的"：{@code juejinCategoryId} 与 {@code juejinTagIds}
 * 各有一个具名消费方，只是那个消费方暂时与实现同域。</p>
 *
 * <h2>失败语义（跨进程后仍然成立，实现必须遵守）</h2>
 * <ul>
 *   <li>{@link #sync} 失败时<b>抛异常</b>，不返回"失败的结果对象"。</li>
 *   <li>文章不存在、或已被软删除（{@code status == -1}）→ {@link IllegalArgumentException}。</li>
 *   <li>{@link #findSucceeded} 是纯查询：没有成功记录时返回 {@code null}，
 *       不联系外部平台、不消耗限流额度；{@code postId} 为 {@code null} 时也返回 {@code null}。</li>
 * </ul>
 *
 * <h2>调用方身份</h2>
 * <p>两个方法都<b>显式</b>收 {@code userId} 作为第一个参数，且必须是<b>参数名</b>
 * {@code userId}（跨进程后提供方读不到调用方的 {@code ThreadLocal}，
 * 而它是文章属主与掘金凭证属主的唯一依据）。</p>
 *
 * <p><b>⚠️ 端口宽度没有任何东西在守。</b>加一个 {@code default} 方法不会编译报错、
 * 不会动任何耦合指标、门禁也不查。方法个数与完整签名由 {@code JuejinBlogSyncPortTest}
 * 用反射钉住。</p>
 */
public interface JuejinBlogSyncPort {

    /**
     * 把一篇本地文章同步到该用户的掘金账号。
     *
     * <p>幂等由内容域保证：同 {@code postId} 再次调用不会重复打扰平台。</p>
     *
     * @param userId            文章属主，也是掘金凭证属主
     * @param postId            本地文章 id
     * @param juejinCategoryId  掘金分类；{@code null} 时回落到文章自己的分类
     * @param juejinTagIds      掘金标签 id 列表；{@code null} 或空时用文章自己的标签名去解析
     * @return 平台标识、平台侧文章 id 与可访问 URL
     * @throws IllegalArgumentException 文章不存在或已被软删除
     */
    BlogSyncResult sync(Long userId, Long postId, String juejinCategoryId, List<String> juejinTagIds);

    /**
     * 查这篇文章是否已经成功同步过，<b>不联系外部平台</b>。
     *
     * @param userId 文章属主
     * @param postId 本地文章 id；{@code null} 时返回 {@code null}
     * @return 没有成功记录时为 {@code null}
     */
    BlogSyncResult findSucceeded(Long userId, Long postId);
}
