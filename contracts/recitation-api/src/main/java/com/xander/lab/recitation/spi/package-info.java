/**
 * {@code recitation} 域对外的服务间契约。
 *
 * <h2>成员</h2>
 * <p><b>材料能力</b>：</p>
 * <ul>
 *   <li>{@link com.xander.lab.recitation.spi.RecitationMaterialPort} —— 背诵材料能力（4 条方法）</li>
 *   <li>{@link com.xander.lab.recitation.spi.MaterialView} —— 收窄的只读视图（14 → 9 个分量）</li>
 *   <li>{@link com.xander.lab.recitation.spi.MaterialDraft} —— 创建入参（去掉 {@code jakarta.validation} 注解）</li>
 *   <li>{@link com.xander.lab.recitation.spi.MaterialFilter} —— 筛选条件（5 → 4 个分量，不带归档维度）</li>
 * </ul>
 * <p><b>测验能力</b>：</p>
 * <ul>
 *   <li>{@link com.xander.lab.recitation.spi.RecitationQuizPort} —— 测验能力（2 条方法 + 2 个常量）</li>
 *   <li>{@link com.xander.lab.recitation.spi.QuizSessionView} —— 出题材料（15 个分量，消费方全读）</li>
 *   <li>{@link com.xander.lab.recitation.spi.QuizAttemptView} —— 历史测验摘要（9 → 6 个分量；
 *       {@code items} 走 {@code itemsJson} **文本**，见该类注释）</li>
 *   <li>{@link com.xander.lab.recitation.spi.QuizOutcomeView} —— 判分结果（11 → 10 个分量，不带审计列）</li>
 *   <li>{@link com.xander.lab.recitation.spi.GradedItemDraft} —— 逐题判分入参（{@code credit} 可空）</li>
 * </ul>
 * <p>两个能力的消费方都是 {@code agent}（工具）与 {@code automation}（定时计划）。</p>
 *
 * <h2>包名为什么是 {@code .spi} 而不是 {@code .api}</h2>
 * <p>{@code com.xander.lab.recitation.api} 已经被 recitation 自己的**前端 HTTP 层**占用
 * （{@code CreateRecitationMaterialRequest}、{@code RecitationMaterialView}、
 * {@code RecitationAttemptView}、{@code UpdateRecitationMaterialRequest}、
 * {@code KnowledgeQuizAttemptView}）。把契约声明在那里会有两个后果：一个包进两个 jar；
 * 扫描器会把那些控制器判成共享内核，于是"控制器 import 自己的 application 层"变成
 * 几十条 {@code recitation.api -> recitation} 的存量边，每条再带一个
 * {@code shared-kernel-reverse-dependency} 违规。与 {@code agent-api}、{@code automation-api}、
 * {@code studio-api} 同一个坑，同一个答案。{@code SHARED_KERNEL} 里写的是
 * {@code recitation.spi}（带点、最长匹配优先，所以不会连带吞掉
 * {@code recitation.application} 或 {@code recitation.domain}）。</p>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li><b>零依赖</b>。只允许 {@code java.}（{@code CONTRACT_ALLOWED_IMPORT_PREFIXES}），
 *       不许 Jackson、不许注解、不许 {@code JsonNode}。</li>
 *   <li><b>不含实现</b>。这里只有类型与端口，任何一段业务逻辑都不属于本模块。</li>
 *   <li><b>契约不能依赖契约</b>。只允许 JDK + 本包；需要别域的等价类型就各写一份。</li>
 *   <li><b>不许 {@code default} 方法</b>。加一个就能悄悄把端口变宽，而门禁与指标都看不见。</li>
 *   <li><b>分量顺序由反射测试钉住</b>。本模块的类型都不是前端可见的（前端看的是
 *       {@code recitation.api} 那套），改形状不需要登记 {@code contract-changes.yaml}，
 *       但顺序换位仍然会静默错位。</li>
 * </ul>
 *
 * <h2>刻意不在端口上的东西</h2>
 * <ul>
 *   <li>{@code recitation.api} 那一层本身。它是前端 HTTP 契约，受众不同，不进契约模块。</li>
 *   <li>录音任务（{@code RecitationAttemptView}）与材料的更新 / 归档 / 删除。
 *       跨域这一侧没有调用方 —— 它们只服务控制器。</li>
 *   <li>{@code listQuizzes}（列出某条知识的测验记录）。调用方只有
 *       {@code RecitationController}，同域。</li>
 *   <li>两参重载 {@code openQuiz(Long, Long)}。它**没有生产调用方**，只有服务自己的测试在用；
 *       端口的三参版传 {@code null} 覆盖同一语义。</li>
 *   <li>{@code KnowledgeQuizAttemptView.items} 的类型是 {@code Object}（没有形状）。
 *       端口上以文本出入（见 {@link com.xander.lab.recitation.spi.QuizAttemptView}）；
 *       把它变成强类型需要先决定"旧行的形状怎么办"。</li>
 * </ul>
 */
package com.xander.lab.recitation.spi;
