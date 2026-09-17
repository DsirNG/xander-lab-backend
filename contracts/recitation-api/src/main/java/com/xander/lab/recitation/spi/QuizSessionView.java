package com.xander.lab.recitation.spi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 开一场测验时交给消费方的出题材料：原文 + 掌握画像 + 最近几次测验。
 *
 * <h2>为什么这一次 15 个分量一个都没减</h2>
 * <p>域侧 {@code KnowledgeQuizService.QuizSession} 也是 15 个分量，但**这不是照抄**：
 * 字段集是从消费方读取点反推的，而唯一跨域消费方 {@code QuizKnowledgeTool}
 * **逐个读了全部 15 个**（它把每一个都写进工具输出，因为出题要用原文、
 * 判分口径要用 testMode、复习建议要用 dueForReview / nextReviewAt）。</p>
 *
 * <p>收窄型契约的判据是"每个字段都有具名消费方"，不是"字段数必须比域侧少"。
 * 反推结果是全读 ⇒ 全带。真正被砍掉的是**域侧那个 record 本身**：它的调用方
 * 只有这两个工具和服务自己的测试，所以按 ADR §7.5 的做法直接换返回类型，
 * 不保留一个同域富方法（见 {@link RecitationQuizPort}）。</p>
 *
 * <h2>⚠️ 位置契约的风险点</h2>
 * <p>分量里有两处"相邻同类型"的换位风险，写错都不会编译失败：</p>
 * <ul>
 *   <li>{@code title} / {@code knowledgeType} / {@code testMode} / {@code content}
 *       —— 四个相邻 {@code String}。换位会让智能体把测试模式当成正文去出题。</li>
 *   <li>{@code lastReviewAt} / {@code nextReviewAt} —— 两个相邻 {@code LocalDateTime}。
 *       换位会让"上次复习时间"变成未来、复习建议整体错位。</li>
 * </ul>
 * <p>两处都由 {@code RecordShapeTest}（钉名字与顺序）加应用侧
 * {@code RecitationQuizPortMappingTest}（用互不相同的字面量钉换算那一行）合起来守。</p>
 *
 * <h2>为什么要另写一份而不是搬</h2>
 * <p>域侧那个 record 内嵌 {@code List<recitation.api.KnowledgeQuizAttemptView>} ——
 * 一个**前端 HTTP 层**的类型。搬进契约会让契约依赖应用模块（ADR-1 与"契约只允许 JDK"
 * 两条都违反），所以这里换成 {@link QuizAttemptView}。</p>
 *
 * @param contentTruncated 正文被截断时为 true，智能体出题时应避开未见部分
 * @param dueForReview     是否已到建议复习时间
 * @param recentQuizzes    最近几次测验，用于针对错处出题
 */
public record QuizSessionView(
        Long quizSessionId,
        Long materialId,
        String title,
        String knowledgeType,
        String testMode,
        String content,
        boolean contentTruncated,
        int contentLength,
        BigDecimal masteryScore,
        String masteryLevel,
        int reviewCount,
        LocalDateTime lastReviewAt,
        LocalDateTime nextReviewAt,
        boolean dueForReview,
        List<QuizAttemptView> recentQuizzes) {
}
