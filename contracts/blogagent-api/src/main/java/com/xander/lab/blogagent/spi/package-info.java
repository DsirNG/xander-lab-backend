/**
 * 博客生成智能体的跨域契约（V9 §18 / §91）。
 *
 * <h2>成员</h2>
 * <ul>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentPort} —— 博客生成任务能力（5 条方法）</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentTaskDraft} —— 创建入参（去掉 {@code jakarta.validation} 注解）</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentTaskView} —— 收窄的任务视图（域实体 25 → 4 个分量）</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentPostView} —— 收窄的已发布文章视图（{@code BlogPostVO} 17 → 5）</li>
 * </ul>
 *
 * <h2>包名：{@code blogagent.spi} 而不是 {@code blogagent.api}</h2>
 * <p>{@code com.xander.lab.blogagent.api} 已经被**前端 HTTP 层**占了
 * （{@code BlogAgentController}、{@code BlogAgentTaskCreateRequest}、{@code BlogAgentTaskVO}、
 * {@code BlogAgentSessionVO}、{@code BlogAgentMessageRequest}）。把契约声明在那里会：
 * （a）一个包进两个 jar；（b）让扫描器把那些 controller 判成共享内核，
 * 于是"controller import 自己的 application 层"会变成几十条
 * {@code blogagent.api -> blogagent} 存量边，每条还配一个
 * {@code shared-kernel-reverse-dependency} 违规。</p>
 * <p>同一个坑踩过四次：{@code agent-api}、{@code automation-api}、{@code recitation-api}、
 * {@code studio-api}（未做）。**新增契约前必做**：
 * {@code ls apps/core-host/src/main/java/com/xander/lab/<domain>/}。</p>
 * <p>{@code SHARED_KERNEL} 里登记的是 {@code blogagent.spi}（带点、按最长前缀匹配，
 * 所以不会吞掉 {@code blogagent.application} / {@code blogagent.domain} /
 * {@code blogagent.infrastructure}）。</p>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li><b>零依赖</b>（除 test 作用域的 junit）。不许出现 Jackson、Spring、MyBatis、
 *       {@code jakarta.validation}、{@code JsonNode}、{@code ObjectMapper}（ADR-1）。</li>
 *   <li><b>不含实现</b>。这里是接口与记录，没有 {@code @Service}、没有 mapper、没有业务规则。</li>
 *   <li><b>契约不许依赖契约</b>。只允许 JDK + 本包 ⇒ 字段相同的类型要各写一份，
 *       不要 import 别的契约模块。</li>
 *   <li><b>不许有 {@code default} 方法</b>。加一个 default 是"端口变宽"最隐蔽的形式：
 *       能编译、指标不动、26 条门禁全绿，只有反射测试会红。</li>
 *   <li><b>改动形状 = ADR §11</b>。这里所有类型都是**新缝**上的收窄类型，前端看不见它们，
 *       所以不涉及"序列化逐字节不变"的证明；但一旦某个类型开始**替代**前端可见的类型，
 *       就必须按镜像规则逐字段同名，并进 {@code contract-changes.yaml}。</li>
 * </ul>
 *
 * <h2>这一刀拆掉了什么</h2>
 * <table>
 *   <caption>2 条实现语句 + 3 条 api 语句，跨 3 个文件</caption>
 *   <tr><th>文件</th><th>原来 import</th></tr>
 *   <tr><td>{@code BlogGenerationTool}</td>
 *       <td>{@code blogagent.application.BlogAgentService}（impl）、
 *           {@code blogagent.api.BlogAgentTaskCreateRequest} / {@code BlogAgentTaskVO}（api）、
 *           {@code blogagent.domain.BlogAgentTask}（domain）</td></tr>
 *   <tr><td>{@code PublishPostTool}</td>
 *       <td>{@code blogagent.application.BlogAgentService}（impl）、
 *           {@code publishing.api.BlogPostVO}（**另一条边**：{@code agent -> publishing}）</td></tr>
 * </table>
 * <p>改完之后依赖方向是 {@code agent -&gt; blogagent-api &lt;- blogagent}，
 * 并且 {@code agent -&gt; publishing} 整条消失（{@code PublishPostTool} 是
 * {@code BlogPostVO} 唯一的跨域读者）。</p>
 *
 * <h2>有意不在这次改动里的</h2>
 * <ul>
 *   <li>图片生成能力（{@code ImageGenerationTool} 走 {@code blogagent.infrastructure}）。
 *       形状与"服务端口"不同 —— 是一个具体客户端 + 一个 {@code @ConfigurationProperties}，
 *       而且它自带一个形状决定：图片以 {@code byte[]} 过界、用量以三 long 的记录过界
 *       （{@code ModelUsage} 住 {@code libs/xander-model}，带 Jackson ⇒ 契约 import 不到，
 *       ADR-1）。所以 {@code agent -> blogagent} 这一刀之后还剩 2 条，不归零。</li>
 *   <li>{@code generateTopicSeries}：唯一调用方 {@code automation.BlogContentPlanService}
 *       与 blogagent 同属 {@code dinqor-content-automation} ⇒ 改它不动任何跨单元指标。</li>
 *   <li>{@code blogagent.api} 那一层本身。它是前端 HTTP 契约，受众不同，
 *       要到 Wave 2 拆它自己的时候才进契约模块。</li>
 * </ul>
 */
package com.xander.lab.blogagent.spi;
