/**
 * 对象存储的服务间契约（V9 §18 / §91）。
 *
 * <h2>这里放什么</h2>
 *
 * 放"实现跨部署单元"的类型：一个类型属于这里，判据不是"看起来像契约"，
 * 而是**它的实现跨部署单元**。本包当前两个成员都是这么来的 ——
 * agent / knowledgebase / publishing / recitation 四个域分别落在四个部署单元，
 * 却都要注入 {@code OssService}、都要读它的返回类型。
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
 * {@link com.xander.lab.integration.api.UploadResponse} 同时是**前端**的 HTTP 契约，
 * 改字段名会直接改到前端拿到的 JSON。按 §11 走
 * {@code docs/contract-changes.yaml}，不要当成普通重构。
 *
 * <h2>刻意还不在这里的东西</h2>
 *
 * <ul>
 *   <li>{@code OssService} 本身。它是**实现**（阿里云 SDK、{@code @Value} 配置、
 *       懒建客户端），属于 integration 域；这里只放端口。</li>
 *   <li>CSDN / 掘金 的凭证与同步服务、{@code MailService}。它们同样被跨域引用
 *       （automation / agent / identity），但都不是接口而是具体实现，
 *       搬它们要先做端口抽取；其中 {@code MailService} 还 import 了
 *       {@code automation.domain.EmailReminderMailTemplates}（一个业务域类型），
 *       在解开那处依赖之前**搬不动**。见 {@code docs/migration/lib-extraction.md}。</li>
 * </ul>
 */
package com.xander.lab.integration.api;
