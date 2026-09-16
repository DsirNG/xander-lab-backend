/**
 * 内容域的服务间契约（V9 §18 / §91）。
 *
 * <p>本包只放<b>跨部署单元</b>要交换的东西：发布端口 {@link com.xander.lab.content.api.BlogPublisher}
 * 与它的输入输出 {@link com.xander.lab.content.api.PublishCommand} /
 * {@link com.xander.lab.content.api.PublishResult}，外加适配器必须遵守的
 * {@link com.xander.lab.content.api.PublishingLimits}。</p>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li><b>零依赖。</b>只允许 JDK 类型。任何框架（Spring MVC / WebFlux / MyBatis / Redis）
 *       或业务域类型都不许进来 —— 契约要被每个服务依赖，包括 WebFlux 的 Gateway，
 *       而 V9 §19 明确 Gateway 一旦被拖入 spring-webmvc 就启动失败。由门禁
 *       {@code contract-dependency-boundary} 检查。</li>
 *   <li><b>不放实现。</b>没有 {@code @Service}、没有 Repository、没有 mapper。这里只有数据与端口。</li>
 *   <li><b>改字段是契约变更。</b>字段增删改都影响两个服务的序列化，走 V9 §11 的
 *       Contract Freeze 流程，不要直接改。</li>
 * </ul>
 *
 * <h2>现在还不在这里的东西</h2>
 * <p>{@code publishing.api.BlogPostDTO} / {@code BlogPostVO} 暂时留在内容域的 api 层。
 * 它们是<b>前端</b>（Web / 小程序）的 HTTP 契约，受众与服务间契约不同；而且
 * {@code BlogPostVO} 同时是 {@code BlogPostMapper} 的投影目标（{@code tagsRaw} 承接
 * {@code GROUP_CONCAT}），拆分前必须先把它在线形态与持久化投影分开。详见
 * {@code docs/migration/lib-extraction.md}。</p>
 */
package com.xander.lab.content.api;
