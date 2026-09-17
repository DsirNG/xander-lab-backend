package com.xander.lab.recitation.spi;

import java.util.List;

/**
 * 测验能力的跨域端口：开场（取出题材料）与收口（落逐题判分）。
 *
 * <p>由 {@code recitation} 域实现（{@code KnowledgeQuizService}），由 {@code agent} 域的
 * {@code QuizKnowledgeTool} / {@code GradeAnswerTool} 注入。依赖方向是
 * {@code agent -> recitation-api <- recitation}。</p>
 *
 * <h2>为什么只有两条方法，而且这里**没有**富方法</h2>
 * <p>域侧 {@code KnowledgeQuizService} 有五条公开方法，进端口的只有两条：
 * {@code openQuiz} 与 {@code recordQuiz}。另外三条不进来 ——</p>
 * <table>
 *   <caption>没进端口的三条，各自的理由不同</caption>
 *   <tr><th>方法</th><th>为什么不进</th></tr>
 *   <tr><td>{@code listQuizzes(Long, Long)}</td>
 *       <td>调用方只有 {@code RecitationController}（同域），返回的是前端可见的
 *           {@code KnowledgeQuizAttemptView}。跨域这一侧没有调用方。</td></tr>
 *   <tr><td>{@code openQuiz(Long, Long)}（两参重载）</td>
 *       <td>**没有生产调用方**，只有服务自己的测试在用。端口的三参版传
 *           {@code null} 会话即可覆盖同一语义，所以不为它单开一条。</td></tr>
 *   <tr><td>{@code normalize} / {@code score} / {@code writeItems} 等私有方法</td>
 *       <td>实现细节，本来就不该跨边界。</td></tr>
 * </table>
 *
 * <p>⚠️ 按 ADR §7.5 的做法，**这里没有保留同域的富方法**。那两个方法原来的调用方只有
 * 这两个工具（跨域）与服务自己的测试，没有控制器在用它们的返回类型，
 * 所以直接换返回类型、删掉域侧那两个 record 即可 —— 与
 * {@code EmailReminderService.listAll} / {@code findByClientRequestId} / {@code delete}
 * 同一种处理。留下一个没有任何调用方的富方法只会变成死代码，
 * 而且还得给它编一个说明受众的后缀（受众是空的）。</p>
 *
 * <h2>方法集就是"调用方被迫实现的那一组"</h2>
 * <table>
 *   <caption>谁要哪一条</caption>
 *   <tr><th>方法</th><th>消费方</th></tr>
 *   <tr><td>{@link #openQuiz}</td><td>{@code QuizKnowledgeTool}（agent）——出题前取原文与掌握画像</td></tr>
 *   <tr><td>{@link #recordQuiz}</td><td>{@code GradeAnswerTool}（agent）——用户答完后逐题判分落库</td></tr>
 * </table>
 *
 * <h2>第一个参数永远是调用方 id</h2>
 * <p>两条方法都显式收 {@code Long userId}。这不是冗余：调用方可能是另一个进程里的服务，
 * {@code UserContext} 的 ThreadLocal 跨不过去，把"当前用户"藏在实现里会让跨进程调用
 * 拿到一个空的或错误的身份。提供方在 userId 为 {@code null} 时抛 401。</p>
 *
 * <h2>抛错语义属于提供方</h2>
 * <ul>
 *   <li>知识不存在、或存在但不属于该用户 ⇒ 404（{@code openQuiz}）。</li>
 *   <li>会话不存在、已过期、属于别的用户、或属于另一个会话 ⇒ 提供方拒绝
 *       （{@code recordQuiz}）。这条"评分只能落到这次真实出题上"的约束是
 *       {@code quiz_session} 表存在的全部理由，端口只是不遮住它。</li>
 *   <li>逐题证据为空、条数超 {@link #MAX_ITEMS}、或某题缺 {@code credit} ⇒ 拒绝。
 *       最后一条见 {@link GradedItemDraft} 的"没判分 ≠ 0 分"。</li>
 * </ul>
 * <p>实现侧不得为了"看起来更纯"把这些改成返回 {@code null} 或 {@code Optional}，
 * 那会让校验悄悄失效。</p>
 *
 * <h2>事务</h2>
 * <p>{@code openQuiz} 在实现上带 {@code @Transactional}：它要先校验材料归属、
 * 再插一行会话，两步必须在同一个事务里。它是**自包含**的（不转调别的公开方法），
 * 所以不存在"自调用绕过代理"的问题。</p>
 * <p>{@code recordQuiz} 在实现上**不带** {@code @Transactional}：它用
 * {@code TransactionTemplate} 显式开事务，因为要在事务里根据上一行的结果决定分支
 * （幂等复用 vs 新插入）。这一点不要为了"统一风格"改成注解 —— 注解表达不了那个分支。</p>
 *
 * <h2>没有 {@code default} 方法，这是硬约束</h2>
 * <p>给端口加一个 {@code default} 方法是**最隐蔽的变宽形式**：编译通过、耦合指标一动不动、
 * 26 条门禁全绿，只有本模块的反射测试会红（见 {@code RecitationQuizPortTest}）。
 * 所以宽度**只能**靠反射测试守，改动这个接口时务必同步改测试里的完整签名清单。</p>
 */
public interface RecitationQuizPort {

    /**
     * 一次最多记录的题目数。
     *
     * <p>从 {@code KnowledgeQuizService} 搬来。搬的理由与 {@code NotificationPort} 的
     * 状态常量完全相同：{@code GradeAnswerTool} 要把它写进工具的 JSON Schema
     * （"最多 N 题"），只要还写着 {@code KnowledgeQuizService.MAX_ITEMS}，
     * 工具就仍然要 import 提供方的实现类，实现语句一条都没少 —— 端口白抽了。</p>
     */
    int MAX_ITEMS = 20;

    /**
     * 总体讲评的字符上限。
     *
     * <p>同上，从 {@code KnowledgeQuizService} 搬来，供工具描述使用。</p>
     */
    int MAX_VERDICT_CHARS = 1000;

    /**
     * 开一场测验并持久化会话，使后续评分只能落到这次真实出题上。
     *
     * <p>不写任何掌握度：开一场测验又放弃，不该有任何代价。</p>
     *
     * @param materialId     要测验的知识 id
     * @param conversationId 发起测验的智能体会话；**可为 {@code null}**（不在对话里调用时），
     *                       但一旦给了，后续 {@link #recordQuiz} 必须给同一个值，
     *                       否则提供方拒绝——这是防止"跨会话拿别人的出题记录刷分"的那道闸
     */
    QuizSessionView openQuiz(Long userId, Long materialId, Long conversationId);

    /**
     * 记录一次测验的逐题判分，并按得分推进掌握等级与下次复习时间。
     *
     * <p>⚠️ 端口上**没有**"总分"入参，这是刻意的：总分由提供方按每题
     * {@link GradedItemDraft#credit()} 算出。想让分数虚高就得先编出一整套逐题证据。</p>
     *
     * @param quizSessionId  {@link #openQuiz} 创建的会话 id
     * @param conversationId 评分所在会话；必须与测验发起会话一致
     * @param verdict        面向用户的总体讲评，可为空
     * @param items          逐题判分证据，不能为空；某题的 {@code credit} 为 {@code null}
     *                       表示这一题没判分，提供方会拒绝整次提交
     */
    QuizOutcomeView recordQuiz(Long userId, Long quizSessionId, Long conversationId,
                               String verdict, List<GradedItemDraft> items);
}
