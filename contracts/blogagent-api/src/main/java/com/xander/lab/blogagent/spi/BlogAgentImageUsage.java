package com.xander.lab.blogagent.spi;

/**
 * 一次图片生成调用返回的 token 用量。
 *
 * <h2>为什么不直接用 {@code com.xander.lab.common.ModelUsage}</h2>
 *
 * <p>它就在那儿、字段一模一样、两个消费方现在都在用 —— 复用显然更省事。但它住在
 * {@code libs/xander-model}，那个模块为了 {@code ModelUsage.fromJson(JsonNode)} 与
 * {@code ModelCallResult.payload} 声明了 {@code jackson-databind} 依赖。契约模块
 * <b>只许 import JDK 与自己的包</b>（门禁 {@code contract-dependency-boundary}），
 * 所以契约根本 import 不到它。</p>
 *
 * <p>这不是"门禁太死"，而是 <b>契约模块必须与序列化框架无关，Jackson 属于出入站适配器</b>。
 * 让契约依赖一个带 Jackson 的库，下一步就是它开始依赖带 MyBatis 的库。</p>
 *
 * <p>⚠️ 于是它和 {@code payment.api.TokenUsage} <b>形状完全相同</b>。这是刻意的重复：
 * 契约不能依赖契约（只允许 JDK + 本包），所以同一个形状只能各写一份。
 * 两个消费方都不该替对方发言 —— 一个是"生成一张图花了多少"，一个是"这次计费记多少"。</p>
 *
 * <h2>字段集与顺序刻意与 {@code ModelUsage} 逐一同名同序</h2>
 *
 * <p>{@code (inputTokens, cachedTokens, outputTokens)} —— 顺序与 {@code ModelUsage} 一致，
 * 所以"换算"这件事是<b>总的</b>（三个字段一一对应，没有遗漏、没有猜测），
 * 提供方在边界上写的就是一次直接映射。</p>
 *
 * <p><b>⚠️ 这是三个相邻的 {@code long}。</b>重排组件顺序<b>不会有任何编译错误</b>，
 * 只会让用量按错误的口径记账 —— 而 token 记错不会抛异常，只会静默少记或多记。
 * 由 {@code RecordShapeTest} 用<b>互不相同</b>的字面量钉住每个位置。</p>
 *
 * <h2>为什么契约要带 {@link #NONE} 与 {@link #isEmpty()}</h2>
 *
 * <p>因为它们不是"方便方法"，而是<b>端口语义的一部分</b>：网关没返回 {@code usage} 时
 * 提供方传 {@code NONE}，消费方据此退回一口价（或对图片直接拒绝扣费）。
 * 所以"空用量"这个概念必须能跨边界表达 —— 否则消费方只能再加一个布尔参数，
 * 把同一个语义拆成两处。</p>
 *
 * <p>与 {@code ModelUsage} 一致：三个分量都 {@code <= 0} 才算空，任一为正即非空。
 * 全零与 {@code NONE} 是等价的，不做区分。</p>
 *
 * @param inputTokens  输入 token 数（含缓存命中的部分）
 * @param cachedTokens 其中命中缓存的输入 token 数
 * @param outputTokens 输出 token 数
 */
public record BlogAgentImageUsage(long inputTokens, long cachedTokens, long outputTokens) {

    /** 网关未返回 {@code usage} 时的占位值。消费方据此退回一口价。 */
    public static final BlogAgentImageUsage NONE = new BlogAgentImageUsage(0, 0, 0);

    public boolean isEmpty() {
        return inputTokens <= 0 && cachedTokens <= 0 && outputTokens <= 0;
    }
}
