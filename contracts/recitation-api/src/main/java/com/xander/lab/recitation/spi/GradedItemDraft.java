package com.xander.lab.recitation.spi;

import java.math.BigDecimal;

/**
 * 一道题的判分证据，由消费方（智能体工具）构造后交给端口落库。
 *
 * <p>与域侧 {@code KnowledgeQuizService.GradedItem} 逐字段同名同序，是它的跨域等价物。
 * 之所以要另写一份而不是搬：契约不能依赖 {@code recitation.application}。</p>
 *
 * <h2>⚠️ {@code credit} 的 {@code null} 是"没判分"，不是"0 分"</h2>
 * <p>这个字段**故意**是可空的，而且消费方**故意**不在缺省时兜底：</p>
 * <ul>
 *   <li>{@code null} = 智能体没给这一题判分。提供方会**拒绝整次提交**
 *       （{@code IllegalArgumentException}），让模型重来一次。</li>
 *   <li>{@code 0} = 智能体明确判了"完全不对"。这是一条正常结果。</li>
 * </ul>
 * <p>这两者混同的后果是**静默把没判的题算成答错**，把用户掌握度往下压 ——
 * 而且不会有任何报错。所以：</p>
 * <ul>
 *   <li>消费方解析 {@code credit} 时，三种写法（数字 {@code 1}、字符串 {@code "0.5"}、
 *       布尔 {@code correct}）都接受，但**一个都没有时留 {@code null}**，
 *       不许补 {@code BigDecimal.ZERO}。</li>
 *   <li>提供方**不许**在换算层写 {@code draft.credit() == null ? ZERO : draft.credit()}。
 *       域侧那条拒绝逻辑就是为这个场景写的，兜底等于把它拆掉。</li>
 * </ul>
 * <p>这是 {@code EmailReminderDraft} 那条"没传 ≠ 传了空"在本模块的第二次出现，
 * 但后果方向相反：那边是静默关掉一个开关，这边是静默压低一个分数。</p>
 *
 * @param credit 得分比例 0 到 1；{@code 0.5} 表示只答对一半；{@code null} 表示这一题没判分
 */
public record GradedItemDraft(
        String question,
        String userAnswer,
        String comment,
        BigDecimal credit) {
}
