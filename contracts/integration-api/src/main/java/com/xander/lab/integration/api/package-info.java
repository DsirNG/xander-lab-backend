/**
 * integration 域的服务间契约（V9 §18 / §91）。
 *
 * <h2>这里放什么</h2>
 *
 * 放"实现跨部署单元"的类型：一个类型属于这里，判据不是"看起来像契约"，
 * 而是**它的实现跨部署单元**。本包六个成员都是这么来的：
 *
 * <ul>
 *   <li>{@code OssPort} / {@code UploadResponse} —— agent / knowledgebase / publishing /
 *       recitation 四个域分别落在四个部署单元，却都要注入 {@code OssService}、
 *       都要读它的返回类型。</li>
 *   <li>{@code CsdnBlogSyncPort} / {@code JuejinBlogSyncPort} / {@code BlogSyncResult} ——
 *       agent 与 automation 落在另外两个部署单元，却都要把本地文章同步到站外平台。</li>
 *   <li>{@code MailPort} —— identity（登录验证码）与 automation（定时提醒）落在另外两个
 *       部署单元，却都要发邮件；实现类 {@code MailService} 在
 *       {@code dinqor-platform-integration}。</li>
 * </ul>
 *
 * ⚠️ 站外同步刻意是**两个**端口（每个平台一个）而不是一个带 {@code platform} 参数的端口：
 * 掘金的同步多收分类与标签 id，合并要么让 CSDN 那条路收下它永远不用的参数，要么砍掉掘金的能力。
 * 完整权衡见 {@link com.xander.lab.integration.api.CsdnBlogSyncPort} 的类注释。
 *
 * ⚠️ {@link com.xander.lab.integration.api.MailPort} 刻意**只带"发什么"、不带"怎么渲染"**：
 * 改造前 {@code MailService} 自己渲染提醒邮件的 HTML，于是它必须 import
 * {@code automation.domain.EmailReminderMailTemplates}（业务域类型）并读
 * {@code email-reminder.display-zone}（配置台账里 owner 记在 {@code content-automation} 名下的键）
 * ——提供方反过来依赖消费方。渲染搬回调用方之后，端口上**一个时间参数都没有**。
 *
 * <h2>硬约束（由门禁 <code>contract-dependency-boundary</code> 强制）</h2>
 *
 * <ol>
 *   <li>源码只 import JDK 与自己的包。连 {@code com.xander.lab.<其他域>} 都不许 ——
 *       那意味着契约反过来指回业务域，依赖方向又反了。</li>
 *   <li>POM 里除 test scope 外不得有任何依赖。契约被每个服务依赖，
 *       包括 V9 §19 那个"被拖进 spring-boot-mvc 就启动失败"的 WebFlux Gateway；
 *       一个长胖了的契约就是 §19 禁止的超级 common.jar 换了个文件夹。</li>
 * </ol>
 *
 * <h2>字段改动就是契约改动（V9 §11）</h2>
 *
 * {@link com.xander.lab.integration.api.UploadResponse} 与
 * {@link com.xander.lab.integration.api.BlogSyncResult} 同时是**前端**的 HTTP 契约
 * （后者由 {@code CsdnBlogSyncController} / {@code JuejinBlogSyncController} 直接返回），
 * 改字段名会直接改到前端拿到的 JSON。按 §11 走
 * {@code docs/contract-changes.yaml}，不要当成普通重构。
 *
 * <h2>刻意还不在这里的东西</h2>
 *
 * <ul>
 *   <li>{@code OssService} 本身。它是**实现**（阿里云 SDK、{@code @Value} 配置、
 *       懒建客户端），属于 integration 域；这里只放端口。</li>
 *   <li>CSDN / 掘金 的**凭证**服务。它们同样被跨域引用（automation / agent），
 *       但都不是接口而是具体实现，搬它们要先做端口抽取。见
 *       {@code docs/migration/lib-extraction.md}。</li>
 *   <li>两个 {@code *BlogSyncService} 的<b>实现</b>。它们 import 了内容域的
 *       {@code ContentPostPort} / {@code PublicationPort}（都是契约，不计跨域边），
 *       以及 {@code MyBatis} 之外的东西很少，但仍然是 {@code @Service} ——
 *       按 ADR-2 域实体与具体实现都不许跨边界。这里只放它们实现的端口。</li>
 * </ul>
 */
package com.xander.lab.integration.api;
