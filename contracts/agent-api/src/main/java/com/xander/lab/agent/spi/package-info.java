/**
 * 远端工具供给的服务间契约（V9 §18 / §91）。
 *
 * <p>本包只放<b>跨部署单元</b>要交换的东西。现在有三个成员：</p>
 *
 * <ul>
 *   <li>端口 {@link com.xander.lab.agent.spi.RemoteToolCatalog} —— 消费方（agent）提问
 *       "这个用户能看到哪些远端工具、目录版本是多少"，提供方（integration 的 MCP 客户端）
 *       回答。</li>
 *   <li>数据 {@link com.xander.lab.agent.spi.RemoteTool} —— 一个被贡献出来的工具：
 *       名字、说明、参数 schema（JSON 文本）、使用准则、审批理由、是否只读，以及怎么调它。</li>
 *   <li>通道 {@link com.xander.lab.agent.spi.RemoteToolCall} —— 调用期间回传进度与检查取消。</li>
 * </ul>
 *
 * <h2>包名为什么是 agent.spi 而不是 agent.api</h2>
 * <p>V9 §18 把模块命名为 {@code agent-api}，另外三个同名的（{@code agent-api} /
 * {@code automation-api} / {@code studio-api}）都落在业务域自己的命名空间里。但
 * <b>这三个域已经占用了 {@code com.xander.lab.<域>.api}</b>——那是它们的<b>前端 HTTP</b>
 * 层（Controller、VO、请求体），受众与服务间契约完全不同。契约再用这个包名会有两个后果：
 * 同一个包出现在两个 jar 里；更糟的是扫描器会把该域自己的 Controller 也算成共享内核，
 * 于是每个 Controller 引用自己的 application 层都会变成一条
 * {@code agent.api -> agent} 的边（把基线数字推高几十条），同时触发
 * {@code shared-kernel-reverse-dependency} 违规。</p>
 *
 * <p>所以契约取下一个空着的段：{@code spi}，并且 {@code SHARED_KERNEL} 里登记的是
 * {@code agent.spi}（带点、最长优先匹配）。{@code automation-api} 与 {@code studio-api}
 * 会撞上同一堵墙，用同一个答案。注意 {@code content-api} / {@code core-api} /
 * {@code payment-api} 没有这个问题——它们的包名与业务域名不同，本来就是空的。</p>
 *
 * <p>名字里带 {@code spi} 也不只是躲碰撞：本端口确实是经典的 SPI 形态——<b>宿主定义、
 * 插件实现</b>，和 {@code content.api.BlogPublisher} 一样。agent 是宿主，integration 是插件。</p>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li><b>零依赖。</b>只允许 JDK 类型。任何框架（Spring MVC / WebFlux / MyBatis / Redis /
 *       Jackson）或业务域类型都不许进来——契约要被每个服务依赖，包括 V9 §19 那个"被拖进
 *       spring-webmvc 就启动失败"的 WebFlux Gateway。由门禁
 *       {@code contract-dependency-boundary} 检查，允许前缀只有 {@code java.}。</li>
 *   <li><b>与序列化框架无关（ADR-1）。</b>所以 schema、参数、返回值三处 JSON 都走
 *       {@code String}。这不是风格问题：{@code AgentTool.parametersSchema()} 返回
 *       {@code JsonNode} 正是它当不了契约类型的原因。序列化属于入站 / 出站适配器。</li>
 *   <li><b>契约不许依赖另一个契约。</b>门禁只允许 JDK 与自身包。</li>
 *   <li><b>不放实现。</b>没有 {@code @Service}、没有 mapper。这里只有数据与端口。</li>
 *   <li><b>方法全部抽象，没有 {@code default}。</b>端口"变宽"不会触发任何一道防线，
 *       而 {@code default} 是变宽的静默形式——它不通知任何实现。要加能力就加抽象方法。</li>
 *   <li><b>改字段 / 改签名是契约变更。</b>走 V9 §11 的 Contract Freeze 流程。
 *       本次抽取<b>不是</b>契约变更：{@code RemoteMcpTool} 原本就持有一个已解析的
 *       {@code JsonNode} schema，改成 {@code toString()} 再解析回来得到同一棵树，
 *       渲染出的工具面 JSON 逐字节不变（由 {@code RemoteToolAdapterTest} 钉住）。</li>
 * </ul>
 *
 * <h2>这一刀拆掉了什么</h2>
 * <p>在此之前 {@code integration.mcp.client.application} 里三处 import 了 agent 的实现类，
 * 共 5 条实现语句，全部跨部署单元
 * （{@code dinqor-platform-integration} 指向 {@code dinqor-agent}）：</p>
 * <ul>
 *   <li>{@code AgentMcpServerService} → {@code AgentToolRegistry}（配置变更后反过来让
 *       消费方的缓存失效）；</li>
 *   <li>{@code McpToolSource} → {@code AgentDynamicToolSource}、{@code AgentTool}
 *       （实现消费方的动态来源接口）；</li>
 *   <li>{@code RemoteMcpTool} → {@code AgentPromptSection}、{@code AgentTool}
 *       （实现消费方的工具接口）。</li>
 * </ul>
 * <p>拆完是 {@code integration → agent-api ← agent}：提供方只认契约，消费方在适配器里把
 * 契约翻成自己的 {@code AgentTool}。{@code agent} 的 50 个工具实现一个都没动。</p>
 *
 * <h2>刻意还不在这里的东西</h2>
 * <ul>
 *   <li>{@code AgentTool} / {@code AgentDynamicToolSource} / {@code AgentToolRegistry}。
 *       它们是 agent 的进程内抽象；端口存在的意义就是让 integration 永远看不到它们。</li>
 *   <li>agent 自己的 {@code com.xander.lab.agent.api.*}。那是<b>前端</b>（Web / 小程序）的
 *       HTTP 契约，受众与服务间契约不同，等那一层本身在 Wave 2 拆分时再进来。</li>
 * </ul>
 *
 * <p>⚠️ 残留一处（记录，不修）：单进程下 Spring 能把 integration 的
 * {@code McpToolSource} 和 agent 侧的适配器 bean 一起装配，拆成两个进程之后这不可能成立。
 * 与 {@code BlogPublisher} 是同一个未决问题，见 {@code docs/migration/lib-extraction.md}。</p>
 */
package com.xander.lab.agent.spi;
