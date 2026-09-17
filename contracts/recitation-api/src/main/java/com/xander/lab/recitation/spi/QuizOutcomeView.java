package com.xander.lab.recitation.spi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次测验落库后的结果，交回消费方告诉用户"得分多少、掌握度动没动、下次什么时候复习"。
 *
 * <h2>为什么是收窄，不是镜像</h2>
 * <p>域侧 {@code KnowledgeQuizService.QuizOutcome} 有 11 个分量，这里 10 个。
 * 少的是 {@code lastReviewAt}：唯一跨域消费方（{@code GradeAnswerTool}）不读它，
 * 服务自己的测试也不读它（{@code grep -n "lastReviewAt" KnowledgeQuizServiceTest} 为空）。
 * 它是审计列，属于域内视角。</p>
 *
 * <p>⚠️ {@code score} 是**服务端算出来的**，不是智能体报的。工具描述里明确禁止模型
 * 自己报总分（"想虚高就得先编出一整套证据"）。这条约束靠
 * {@link RecitationQuizPort#recordQuiz} 只收逐题 {@link GradedItemDraft} 来保证：
 * 端口上**根本没有**一个"总分"入参可以传。</p>
 *
 * <p>⚠️ {@code masteryLevel} 与 {@code previousMasteryLevel} 是两个相邻的 {@code String}，
 * 位置写反不会有编译错误，只会让用户看到"从 APPLYING 降到了 FAMILIAR"这种反向文案。
 * 分量名与顺序由 {@code RecordShapeTest} 钉住，换算落点由应用侧映射测试用
 * **互不相同的字面量**钉住。</p>
 */
public record QuizOutcomeView(
        Long attemptId,
        Long materialId,
        String title,
        BigDecimal score,
        int questionCount,
        int correctCount,
        String masteryLevel,
        String previousMasteryLevel,
        int reviewCount,
        LocalDateTime nextReviewAt) {
}
