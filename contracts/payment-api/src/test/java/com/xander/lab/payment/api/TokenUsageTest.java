package com.xander.lab.payment.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link TokenUsage} 的<b>分量顺序</b>与"空用量"语义。
 *
 * <p>为什么一个三字段的 record 值得单独写测试：因为它是 {@code (long, long, long)}。
 * 重排分量<b>不会有任何编译错误</b>，而它的用途是 token 计费 —— 换位之后
 * "缓存命中的输入 token"会被当成"输出 token"按输出价折算，账单静默偏几倍，
 * 不抛异常、不报错、门禁全绿。</p>
 *
 * <p>真正会踩到的路径不是有人故意重排，而是调用方手写换算时把实参顺序写反：</p>
 *
 * <pre>{@code
 * new TokenUsage(usage.inputTokens(), usage.cachedTokens(), usage.outputTokens())  // 对
 * new TokenUsage(usage.inputTokens(), usage.outputTokens(), usage.cachedTokens())  // 也编译
 * }</pre>
 *
 * <p>所以下面用<b>互不相同的字面量</b>逐个钉位置：只要顺序变了，至少有一条断言红。
 * 用 {@code (1, 1, 1)} 之类的值钉是钉不住的 —— 那种测试在重排之后照样全绿。</p>
 *
 * <p>契约侧钉的是"名字与顺序"，实现侧钉的是"每个位置折算成什么价"：
 * {@code BillingServiceTest.cachedInputTokensAreNotChargedAgainAtTheFullInputRate}
 * 用 {@code (1000, 400, 0)} 断言缓存部分按缓存价而不是输入价计费 —— 两条合起来，
 * 中间那个分量到底是谁才没有歧义。</p>
 */
class TokenUsageTest {

    /** 三个互不相同的字面量：任意两位换位都会被下面四条断言中的至少一条抓到。 */
    private static final long INPUT = 11L;
    private static final long CACHED = 22L;
    private static final long OUTPUT = 33L;

    @Test
    void eachComponentKeepsItsOwnPosition() {
        TokenUsage usage = new TokenUsage(INPUT, CACHED, OUTPUT);

        assertEquals(INPUT, usage.inputTokens(), "第 1 个分量必须是输入 token");
        assertEquals(CACHED, usage.cachedTokens(), "第 2 个分量必须是缓存命中的输入 token");
        assertEquals(OUTPUT, usage.outputTokens(), "第 3 个分量必须是输出 token");
    }

    /**
     * 三个访问器必须两两可区分。
     *
     * <p>上面那条已经覆盖了，这条是防"三个分量都指向同一个字段"这种更粗的错 ——
     * 那种情况下 {@code new TokenUsage(11, 22, 33)} 依然能构造，只是三个 getter
     * 返回同一个值。</p>
     */
    @Test
    void theThreeAccessorsArePairwiseDistinguishable() {
        TokenUsage usage = new TokenUsage(INPUT, CACHED, OUTPUT);

        assertNotEquals(usage.inputTokens(), usage.cachedTokens());
        assertNotEquals(usage.inputTokens(), usage.outputTokens());
        assertNotEquals(usage.cachedTokens(), usage.outputTokens());
    }

    /**
     * 分量名与顺序整体比对 —— 这是给"换算"这件事的凭据。
     *
     * <p>调用方在边界上写的是按<b>名字</b>取、按<b>位置</b>放的
     * {@code new TokenUsage(u.inputTokens(), u.cachedTokens(), u.outputTokens())}。
     * 只要这里的名字序列变了，那句换算就静默错了，所以连顺序一起钉。</p>
     */
    @Test
    void theComponentNamesAndTheirOrderArePinned() {
        List<String> names = Arrays.stream(TokenUsage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("inputTokens", "cachedTokens", "outputTokens"), names,
                "分量顺序变了：所有调用方那句按位置写的换算会静默错账");
        assertEquals(3, names.size());
    }

    /** 三个分量都是原始 {@code long}：装箱会让"未提供"多出一种表达（{@code null}），语义变模糊。 */
    @Test
    void everyComponentIsAPrimitiveLong() {
        for (RecordComponent component : TokenUsage.class.getRecordComponents()) {
            assertEquals(long.class, component.getType(),
                    component.getName() + " 必须是原始 long");
        }
    }

    @Test
    void noneIsAllZeroAndCountsAsEmpty() {
        assertEquals(0L, TokenUsage.NONE.inputTokens());
        assertEquals(0L, TokenUsage.NONE.cachedTokens());
        assertEquals(0L, TokenUsage.NONE.outputTokens());
        assertTrue(TokenUsage.NONE.isEmpty());
    }

    /**
     * "空"的判据：三个分量都 {@code <= 0} 才算空，任一为正即非空。
     *
     * <p>与 {@code com.xander.lab.common.ModelUsage} 保持一致 —— 两份实现用不同判据，
     * 就会出现"网关说没用量、计费说有用量"这种只在边界上暴露的错。</p>
     */
    @Test
    void emptyMeansAllThreeComponentsAreNonPositive() {
        assertTrue(new TokenUsage(0, 0, 0).isEmpty());
        assertTrue(new TokenUsage(-1, 0, 0).isEmpty(), "负数是异常输入，按 0 处理");
        assertFalse(new TokenUsage(1, 0, 0).isEmpty(), "只有输入也要计费");
        assertFalse(new TokenUsage(0, 1, 0).isEmpty(), "只有缓存也要计费");
        assertFalse(new TokenUsage(0, 0, 1).isEmpty(), "只有输出也要计费");
    }

    /** 全零与 {@link TokenUsage#NONE} 等价：调用方不必关心拿到的是哪一个。 */
    @Test
    void aZeroUsageEqualsNone() {
        assertEquals(TokenUsage.NONE, new TokenUsage(0, 0, 0));
        assertEquals(TokenUsage.NONE.hashCode(), new TokenUsage(0, 0, 0).hashCode());
    }

    /** 它是值对象：相等看三个分量，不看身份。 */
    @Test
    void equalityFollowsTheThreeComponents() {
        assertEquals(new TokenUsage(1, 2, 3), new TokenUsage(1, 2, 3));
        assertNotEquals(new TokenUsage(1, 2, 3), new TokenUsage(1, 2, 4));
        assertNotEquals(new TokenUsage(1, 2, 3), new TokenUsage(2, 1, 3), "换位后必须不相等");
    }

    /**
     * 它必须住在契约包里。
     *
     * <p>一旦有人把它挪到某个业务域（或挪回 {@code libs/xander-model}），
     * 端口就又 import 不到了，于是"顺手放宽门禁"的压力立刻出现 ——
     * 而放宽的那一刻 ADR-1 就没了。所以钉住包名。</p>
     */
    @Test
    void itStaysInTheContractPackage() {
        assertEquals("com.xander.lab.payment.api", TokenUsage.class.getPackageName());
        assertTrue(TokenUsage.class.isRecord(), "必须是 record：值语义是它作为端口入参的前提");
    }
}
