/**
 * automation 能力的服务间契约（V9 §11 / §18 / §91）。
 *
 * <h2>成员</h2>
 * <ul>
 *   <li>{@link com.xander.lab.automation.spi.EmailReminderPort} —— 定时邮件提醒能力</li>
 *   <li>{@link com.xander.lab.automation.spi.ContentPlanPort} —— 定时发文计划能力</li>
 *   <li>{@link com.xander.lab.automation.spi.NotificationPort} —— 写一条持久化用户通知的能力</li>
 *   <li>读模型：{@link com.xander.lab.automation.spi.EmailReminderView} /
 *       {@link com.xander.lab.automation.spi.ContentPlanView}</li>
 *   <li>写入草稿：{@link com.xander.lab.automation.spi.EmailReminderDraft} /
 *       {@link com.xander.lab.automation.spi.ContentPlanDraft} /
 *       {@link com.xander.lab.automation.spi.ContentPlanEdit}</li>
 * </ul>
 *
 * <h2>包名为什么是 {@code automation.spi} 而不是 {@code automation.api}</h2>
 * <p>{@code com.xander.lab.automation.api} <b>已经被占用</b> —— 那是 automation 域自己的
 * <b>前端 HTTP 层</b>（20 个文件：{@code EmailReminderController} /
 * {@code BlogContentPlanController} 以及它们的 VO 与请求体）。
 * 把契约声明在那里会同时坏两件事：</p>
 * <ol>
 *   <li>一个包名出现在两个 jar 里；</li>
 *   <li>扫描器会把那个域的控制器当成<b>共享内核</b>，于是"控制器 import 自己的应用层"
 *       变成一条条 {@code automation.api -> automation} 的假边（待还债凭空多出几十条），
 *       外加每条一个 {@code shared-kernel-reverse-dependency} 违规。</li>
 * </ol>
 * <p>所以契约取下一段 {@code spi}，{@code SHARED_KERNEL} 登记带点条目
 * {@code automation.spi}（按最长前缀匹配，因此<b>不会</b>吞掉
 * {@code automation.application}）。{@code agent-api} 与 {@code studio-api} 撞的是同一处，
 * 答案相同。{@code integration.api} 是例外 —— 那个域按集成方式分子包
 * （{@code file} / {@code mail} / {@code mcp} / {@code oss}），{@code .api} 是空的。</p>
 *
 * <h2>硬约束（门禁 {@code contract-dependency-boundary} 检查）</h2>
 * <ul>
 *   <li><b>零依赖</b>：本包源码只允许 import JDK 与自己的包。POM 里除 test scope 外
 *       不得有任何依赖。</li>
 *   <li><b>与序列化框架无关</b>（ADR-1）：不许 Jackson 依赖、不许 Jackson 注解、
 *       不许 {@code ObjectMapper} / {@code JsonNode}。这里的记录类型因此<b>没有</b>
 *       {@code jakarta.validation} 注解，也没有任何序列化注解。</li>
 *   <li><b>不放实现</b>：没有 {@code @Service}、没有 Repository、没有 mapper。</li>
 *   <li><b>不能依赖别的契约</b>：只允许 JDK + 本包。</li>
 *   <li><b>端口不许有 {@code default} 方法</b>：那是最隐蔽的变宽方式 ——
 *       编译通过、指标不动、门禁不报。</li>
 *   <li><b>改字段 / 改签名 = 契约变更</b>：走 §11 Contract Freeze 流程。</li>
 * </ul>
 *
 * <h2>读模型是收窄的，不是镜像的 —— 而且这是判断，不是省事</h2>
 * <p>镜像规则（ADR-2 §2.5）的适用条件是"新类型<b>替换</b>了一个前端可见类型"，
 * 那时"序列化逐字节不变"可证明，于是这次改动可被证明是纯重构。
 * 本模块的记录类型不替换任何东西：{@code automation.api.EmailReminderTaskVO}（20 字段）与
 * {@code BlogContentPlanVO}（24 字段）留在原地继续服务前端，这里的类型只服务三个智能体工具。
 * 所以它们只带工具真正读到的字段（10 / 11 个），每个字段都有具名消费者。
 * 把 24 个字段照抄一遍去保留一个不存在的序列化表面，只会换来一个位置顺序风险很高的记录。</p>
 *
 * <h2>这个契约拆掉了什么（两刀）</h2>
 *
 * <p><b>第九刀 —— 6 条语句 / 6 个文件，方向全是 agent → automation：</b></p>
 * <table>
 *   <caption>端口 {@code EmailReminderPort} / {@code ContentPlanPort}</caption>
 *   <tr><th>文件</th><th>原来 import</th></tr>
 *   <tr><td>{@code EmailReminderTool}</td><td>{@code automation.application.EmailReminderService}</td></tr>
 *   <tr><td>{@code QueryEmailRemindersTool}</td><td>同上</td></tr>
 *   <tr><td>{@code UpdateEmailReminderTool}</td><td>同上</td></tr>
 *   <tr><td>{@code QueryPlansTool}</td><td>{@code automation.application.BlogContentPlanService}</td></tr>
 *   <tr><td>{@code SchedulePlanTool}</td><td>同上</td></tr>
 *   <tr><td>{@code UpdatePlanTool}</td><td>同上</td></tr>
 * </table>
 * <p>全部跨 {@code dinqor-agent} / {@code dinqor-content-automation} 部署单元边界。
 * 改完之后依赖方向是 {@code agent -&gt; automation-api &lt;- automation}。</p>
 *
 * <p><b>第十刀 —— 2 条语句 / 1 个文件，方向是 knowledgebase → automation：</b></p>
 * <table>
 *   <caption>端口 {@code NotificationPort}（<b>零构建接线</b>：只是往本模块加类型）</caption>
 *   <tr><th>文件</th><th>原来 import</th></tr>
 *   <tr><td>{@code KnowledgeIngestionService}</td>
 *       <td>{@code automation.application.BlogNotificationService}<br>
 *           {@code automation.domain.BlogNotification}</td></tr>
 * </table>
 * <p>这刀把 {@code automation} 的<b>扇入</b>又削掉一路：原本 4 个单元指向它，现在
 * {@code agent} 已改走契约，只剩 {@code admin}（SSE，要 {@code SseEmitter} 框架类型）与
 * {@code integration}（邮件模板真环）两条，两条都卡在别的决定上。</p>
 * <p>两个常量 {@code TYPE_KNOWLEDGE_STRUCTURE_PENDING} 与 {@code RESOURCE_KNOWLEDGE_FILE}
 * 随之从 {@code BlogNotification} 实体<b>搬到这里</b> —— 不搬的话消费方仍要 import 实体，
 * 一条语句都减不掉。它们在 automation 内部无人使用，所以搬得干净。</p>
 *
 * <h2>有意不在这次改动里的</h2>
 * <ul>
 *   <li>SSE 广播端口（{@code admin} 要）。它要的是 {@code SseEmitter}（框架类型），
 *       形状与本模块三个端口完全不同，单独一刀。</li>
 *   <li>邮件模板。{@code integration.mail.MailService} import 了
 *       {@code automation.domain.EmailReminderMailTemplates}，这是<b>反方向</b>，
 *       而且闭成了一个真环：automation 的 {@code EmailReminderScheduler} 调
 *       {@code MailService#sendReminderMail}，后者又回头调 automation 渲染正文。
 *       断环要改 {@code MailService} 的 API，单独一刀做。</li>
 * </ul>
 */
package com.xander.lab.automation.spi;
