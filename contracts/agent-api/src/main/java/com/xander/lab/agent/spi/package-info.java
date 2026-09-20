/**
 * 远端工具供给的服务间契约。
 *
 * <p>本包只放<b>跨部署单元</b>要交换的东西。三个成员：</p>
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
 * <p>{@code com.xander.lab.agent.api} 已经被本域的<b>前端 HTTP</b> 层占用
 * （Controller、VO、请求体），受众与服务间契约完全不同。契约再用这个包名会有两个后果：
 * 同一个包出现在两个 jar 里；更糟的是扫描器会把该域自己的 Controller 也算成共享内核，
 * 于是每个 Controller 引用自己的 application 层都会变成一条
 * {@code agent.api -> agent} 的假边，同时触发 {@code shared-kernel-reverse-dependency} 违规。</p>
 *
 * <p>所以契约取下一个空着的段：{@code spi}，并且 {@code SHARED_KERNEL} 里登记的是
 * {@code agent.spi}（带点、最长优先匹配，因此不会吞掉 {@code agent.application}）。
 * {@code automation-api} / {@code blogagent-api} / {@code recitation-api} / {@code studio-api}
 * 撞的是同一处，答案相同；{@code content-api} / {@code core-api} / {@code payment-api}
 * 没有这个问题 —— 它们的包名与业务域名不同，本来就是空的。
 * <b>新增契约前先看</b> {@code apps/core-host/src/main/java/com/xander/lab/<domain>/} 下面
 * 有没有 {@code api} 包。</p>
 *
 * <p>名字里带 {@code spi} 也不只是躲碰撞：本端口确实是经典的 SPI 形态 —— <b>宿主定义、
 * 插件实现</b>。agent 是宿主，integration 是插件。</p>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li><b>零依赖。</b>只允许 JDK 类型。任何框架（Spring MVC / WebFlux / MyBatis / Redis /
 *       Jackson）或业务域类型都不许进来 —— 契约要被每个服务依赖，其中包括不能沾
 *       spring-webmvc 的 WebFlux Gateway。由门禁
 *       {@code contract-dependency-boundary} 检查，允许前缀只有 {@code java.}。</li>
 *   <li><b>与序列化框架无关。</b>所以 schema、参数、返回值三处 JSON 都走
 *       {@code String}。这不是风格问题：{@code AgentTool.parametersSchema()} 返回
 *       {@code JsonNode} 正是它当不了契约类型的原因。序列化属于入站 / 出站适配器。</li>
 *   <li><b>契约不许依赖另一个契约。</b>门禁只允许 JDK 与自身包。</li>
 *   <li><b>不放实现。</b>没有 {@code @Service}、没有 mapper。这里只有数据与端口。</li>
 *   <li><b>方法全部抽象，没有 {@code default}。</b>端口"变宽"不会触发任何一道防线，
 *       而 {@code default} 是变宽的静默形式 —— 它不通知任何实现。要加能力就加抽象方法。</li>
 *   <li><b>改字段 / 改签名是契约变更。</b>改之前先看两个服务各自的序列化面。</li>
 * </ul>
 *
 * <h2>刻意还不在这里的东西</h2>
 * <ul>
 *   <li>{@code AgentTool} / {@code AgentDynamicToolSource} / {@code AgentToolRegistry}。
 *       它们是 agent 的进程内抽象；端口存在的意义就是让 integration 永远看不到它们。</li>
 *   <li>agent 自己的 {@code com.xander.lab.agent.api.*}。那是<b>前端</b>（Web / 小程序）的
 *       HTTP 契约，受众与服务间契约不同。</li>
 * </ul>
 *
 * <p>⚠️ 已知限制：单进程下 Spring 能把 integration 的 {@code McpToolSource} 与 agent 侧的
 * 适配器 bean 一起装配，拆成两个进程之后这不可能成立。</p>
 */
package com.xander.lab.agent.spi;
