package com.xander.lab.content.api;

/**
 * 内容域对外公开的“发布与查证”端口。
 *
 * <h2>为什么这个接口必须住在中立模块</h2>
 * <p>兼容期它由 Core 中的 {@code publishing.application.PublicationService} 实现，调用方是 integration 域的
 * {@code CsdnBlogSyncService} / {@code JuejinBlogSyncService}（{@code dinqor-platform-integration}）。
 * 拆服务之后让它们 import 内容域的具体 {@code @Service}，就是“一个服务直接调另一个服务的内部类”。</p>
 *
 * <h2>为什么是抽端口，不是把 {@code PublicationService} 搬过来</h2>
 * <p>它是具体实现：{@code @Service}，注入了 {@code List<BlogPublisher>}（Spring 按类型收集）、
 * {@code BlogPublishRecordMapper} 与 {@code StringRedisTemplate}。搬进契约等于把 MyBatis 与 Redis
 * 拖进每个消费者。</p>
 *
 * <h2>与 {@link BlogPublisher} 的关系：一个是“怎么发”，一个是“发过没有”</h2>
 * <p>这个分工容易看反，值得写清楚：</p>
 * <ul>
 *   <li>{@link BlogPublisher} 是<b>出站 SPI</b>，由内容域<b>定义</b>、由 integration 域<b>实现</b>
 *       （CSDN / 掘金的平台适配器在 {@code dinqor-platform-integration}）。它只管“把这段内容
 *       推到那个平台”，无状态、不管幂等、不碰数据库。</li>
 *   <li>本端口是<b>编排</b>，由内容域实现也由内容域拥有：查 {@code blog_publish_record} 判幂等、
 *       按 {@code platform} 选发布器、落 PENDING / SUCCEEDED / FAILED、对 CSDN 加限流。
 *       它<b>先查记录再决定要不要发</b>，所以重复调用不会重复打扰平台。</li>
 * </ul>
 *
 * <p>这也解释了为什么 integration 域要绕一圈回来调内容域：它的同步服务需要的是
 * “发过没有 + 别再发一次”，那是内容域的账，不是适配器的事。</p>
 *
 * <h2>失败语义（跨进程后仍然成立，实现必须遵守）</h2>
 * <ul>
 *   <li>{@link #publish} 失败时<b>抛异常</b>，不返回 {@code null} —— 失败态已经落进
 *       {@code blog_publish_record}（{@code FAILED} + {@code errorCode}），调用方需要的是
 *       中断而不是继续。异常类型由发布器决定，端口不规定。</li>
 *   <li>平台不支持（{@code platform} 没有对应 {@link BlogPublisher}）→
 *       {@link IllegalArgumentException}。</li>
 *   <li>{@link #findSucceeded} 是纯查询：没有成功记录时返回 {@code null}，
 *       且<b>不会</b>联系外部平台。它给“对账”用 —— 用户说“我发过了”，
 *       调用方据此确认而不重新发一遍。</li>
 *   <li>CSDN 的限流（每用户每日 5 篇、连续同步间隔 30 秒）在实现里，命中时抛
 *       {@code ResponseStatusException(429)}。跨进程后这个异常必须由适配器映射成 429，
 *       不能变成 500 —— 这是端口语义的一部分，不是实现细节。</li>
 * </ul>
 *
 * <p><b>⚠️ 端口宽度没有任何东西在守。</b>加一个 {@code default} 方法不会编译报错、不会动任何指标、
 * 门禁也不查。方法个数与完整签名由 {@code PublicationPortTest} 用反射钉住。</p>
 *
 * <p>刻意不在端口上的方法：{@code enforceCsdnSyncPolicy}（私有）、以及
 * {@code PublicationService} 没有的其他发布能力。同域的 {@code BlogPlanRunService} 与
 * {@code BlogAgentService} 仍然注入具体类 —— 它们与内容域同在
 * {@code dinqor-content-automation}，拆完还在一个进程里。</p>
 */
public interface PublicationPort {

    /**
     * 把一次发布请求交给指定平台，幂等。
     *
     * <p>幂等键是 {@code (command.taskId(), platform)}：已有 {@code SUCCEEDED} 记录且
     * {@code externalId} 非空时<b>直接返回既有结果</b>，不再请求平台，也不消耗限流额度
     * （限流检查刻意放在幂等返回之后）。</p>
     *
     * <p>{@code command.taskId()} 在本地发布路径上是文章主键，在站外同步路径上是文章主键的
     * <b>相反数</b>（{@code CsdnBlogSyncService} 传 {@code -postId}）—— 这样同一篇文章的
     * “本站发布”与“同步到 CSDN”在同一个表里不会互相撞键。这个约定在调用方，端口不改它。</p>
     *
     * @param platform 平台标识，必须能匹配到某个 {@link BlogPublisher#platform()}
     */
    PublishResult publish(PublishCommand command, String platform);

    /**
     * 查一次发布是否已经成功，<b>不联系外部平台</b>。
     *
     * @param taskId 与 {@link #publish} 同一个幂等键；{@code null} 时返回 {@code null}
     * @return 没有成功记录时为 {@code null}
     */
    PublishResult findSucceeded(Long userId, Long taskId, String platform);
}
