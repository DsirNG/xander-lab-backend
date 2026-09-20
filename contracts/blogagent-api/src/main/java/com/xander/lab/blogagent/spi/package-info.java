/**
 * 博客生成智能体的跨域契约。
 *
 * <h2>成员</h2>
 * <ul>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentPort} —— 博客生成任务能力（5 条方法）</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentTaskDraft} —— 创建入参（去掉 {@code jakarta.validation} 注解）</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentTaskView} —— 收窄的任务视图（域实体 25 → 4 个分量）</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentPostView} —— 收窄的已发布文章视图（{@code BlogPostVO} 17 → 5）</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentImagePort} —— <b>图片生成能力</b>（2 条方法）。
 *       ⚠️ 唯一一个不收 {@code userId} 的端口，理由写在它自己的 Javadoc 里，并由
 *       {@code BlogAgentImagePortTest.takesNoCallerIdAndThatIsDeliberate} 反向守着</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentImage} —— 一次图片生成的结果（6 个分量）</li>
 *   <li>{@link com.xander.lab.blogagent.spi.BlogAgentImageUsage} —— 用量（3 个 long），
 *       与 {@code payment.api.TokenUsage} 形状相同但**刻意各写一份**（契约不许依赖契约）</li>
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
 * <p>{@code agent-api} / {@code automation-api} / {@code recitation-api} / {@code studio-api}
 * 撞的是同一个坑，答案相同。**新增契约前必做**：
 * {@code ls apps/core-host/src/main/java/com/xander/lab/<domain>/}。</p>
 * <p>{@code SHARED_KERNEL} 里登记的是 {@code blogagent.spi}（带点、按最长前缀匹配，
 * 所以不会吞掉 {@code blogagent.application} / {@code blogagent.domain} /
 * {@code blogagent.infrastructure}）。</p>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li><b>零依赖</b>（除 test 作用域的 junit）。不许出现 Jackson、Spring、MyBatis、
 *       {@code jakarta.validation}、{@code JsonNode}、{@code ObjectMapper}。</li>
 *   <li><b>不含实现</b>。这里是接口与记录，没有 {@code @Service}、没有 mapper、没有业务规则。</li>
 *   <li><b>契约不许依赖契约</b>。只允许 JDK + 本包 ⇒ 字段相同的类型要各写一份，
 *       不要 import 别的契约模块。</li>
 *   <li><b>不许有 {@code default} 方法</b>。加一个 default 是"端口变宽"最隐蔽的形式：
 *       能编译、指标不动、门禁全绿，只有反射测试会红。</li>
 *   <li><b>改形状要看序列化面</b>。这里所有类型都是**新缝**上的收窄类型，前端看不见它们，
 *       所以不涉及"序列化逐字节不变"的证明；但一旦某个类型开始**替代**前端可见的类型，
 *       就必须逐字段同名，并确认两端读到的字段集一致。</li>
 * </ul>
 *
 * <h2>端口上没有的东西</h2>
 * <ul>
 *   <li>{@code generateTopicSeries}：唯一调用方 {@code automation.BlogContentPlanService}
 *       与 blogagent 同属 {@code dinqor-content-automation}，走进程内调用，不经端口。</li>
 *   <li>{@code blogagent.api} 那一层本身。它是前端 HTTP 契约，受众不同，不进契约模块。</li>
 *   <li>同域的 {@code BlogAgentService} 自己生成博客插图那条路径（{@code illustrate}）。
 *       它与图片能力同域，走的是 {@code BlogAgentImageClient} 本身，不经端口 ——
 *       端口只服务跨域消费方。⚠️ 这也是为什么 {@code GeneratedImage} 不需要改：
 *       端口实现做换算，同域路径继续用原类型。</li>
 * </ul>
 */
package com.xander.lab.blogagent.spi;
