/**
 * 内容域的服务间契约（V9 §18 / §91）。
 *
 * <p>本包只放<b>跨部署单元</b>要交换的东西。现在有两组：</p>
 *
 * <p><b>发布（§15）</b>：端口 {@link com.xander.lab.content.api.BlogPublisher} 与它的输入输出
 * {@link com.xander.lab.content.api.PublishCommand} / {@link com.xander.lab.content.api.PublishResult}，
 * 外加适配器必须遵守的 {@link com.xander.lab.content.api.PublishingLimits}。</p>
 *
 * <p><b>媒体库（§22）</b>：端口 {@link com.xander.lab.content.api.MediaAssetPort}、
 * 跨域引用 {@link com.xander.lab.content.api.MediaAssetRef}、以及"已存储图片"的输入
 * {@link com.xander.lab.content.api.StoredImage}。调用方在 agent / blogagent / studio
 * 三个部署单元里。</p>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li><b>零依赖。</b>只允许 JDK 类型。任何框架（Spring MVC / WebFlux / MyBatis / Redis）
 *       或业务域类型都不许进来 —— 契约要被每个服务依赖，包括 WebFlux 的 Gateway，
 *       而 V9 §19 明确 Gateway 一旦被拖入 spring-webmvc 就启动失败。由门禁
 *       {@code contract-dependency-boundary} 检查。</li>
 *   <li><b>与序列化框架无关（ADR-1）。</b>契约只描述 Java 层面的形状，不规定用哪种序列化技术
 *       传输。所以 {@code Jackson} / {@code JsonNode} / {@code @JsonProperty} /
 *       {@code @JsonCreator} 一律不许出现在这里，DTO 上也不加 Jackson 注解。
 *       序列化属于入站 / 出站适配器与 infrastructure 的职责。
 *       理由：一旦允许一个注解，"再放一个 Swagger 注解""再放一个 Validation 注解"就没有边界了，
 *       最后 {@code *-api} 只是另一个框架模块。</li>
 *   <li><b>契约不许依赖另一个契约。</b>门禁只允许 JDK 与自身包，所以
 *       {@code content-api} 不能 import {@code integration.api.UploadResponse} ——
 *       这就是 {@link com.xander.lab.content.api.StoredImage} 存在的原因（见它的注释）。</li>
 *   <li><b>不放实现。</b>没有 {@code @Service}、没有 Repository、没有 mapper。这里只有数据与端口。</li>
 *   <li><b>改字段是契约变更。</b>字段增删改都影响两个服务的序列化，走 V9 §11 的
 *       Contract Freeze 流程，不要直接改。</li>
 * </ul>
 *
 * <h2>媒体库这一刀拆掉了什么</h2>
 * <p>在此之前 {@code publishing.application.BlogMediaService} 收
 * {@code blogagent.infrastructure.BlogAgentImageClient.GeneratedImage}，
 * 而 blogagent 又 import {@code BlogMediaService} —— 两个域成环。端口改成只收
 * {@code byte[]} + {@code contentType} 之后（内容域本来就只用这两个字段），
 * 内容域不再 import blogagent，环拆成单向：
 * {@code agent / blogagent / studio → content-api ← publishing}。</p>
 *
 * <h2>现在还不在这里的东西</h2>
 * <p>{@code publishing.api.BlogPostDTO} / {@code BlogPostVO} 暂时留在内容域的 api 层。
 * 它们是<b>前端</b>（Web / 小程序）的 HTTP 契约，受众与服务间契约不同；而且
 * {@code BlogPostVO} 同时是 {@code BlogPostMapper} 的投影目标（{@code tagsRaw} 承接
 * {@code GROUP_CONCAT}），拆分前必须先把它在线形态与持久化投影分开。详见
 * {@code docs/migration/lib-extraction.md}。</p>
 */
package com.xander.lab.content.api;
