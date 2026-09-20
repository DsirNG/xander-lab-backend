package com.xander.lab.payment.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link BillingBiz} 的全部字面量。
 *
 * <p>这不是一组"常量等于自己"的空测试。这些字符串被持久化为
 * {@code points_ledger.biz_type} 的列值，所以改名**不会编译报错** ——
 * 它只会让库里已有的流水行静默失配，而对账和统计按旧值查不到。
 * 这属于"静默降低既有能力"，常量取值属于契约改动。</p>
 *
 * <p>下面用反射取**全部** {@code public static final String} 字段并整体比对，
 * 而不是逐个断言：这样新增、改名、删除任何一项都会失败，不需要有人记得回来补一行。
 * 逐个断言漏掉"新增了一项但没人补断言"这种情况，而那正是最可能发生的一种。</p>
 */
class BillingBizTest {

    /** 期望的完整词表：字段名 -> 落库值。 */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("GRANT_REGISTER", "grant"),
            Map.entry("MERGE_CREDIT", "merge-credit"),
            Map.entry("BLOG_AGENT_RUN", "blog-agent-run"),
            Map.entry("BLOG_AGENT_REVISE", "blog-agent-revise"),
            Map.entry("BLOG_AGENT_COMPLIANCE", "blog-agent-compliance"),
            Map.entry("BLOG_AGENT_ILLUSTRATION", "blog-agent-illustration"),
            Map.entry("BLOG_TOPIC", "blog-topic"),
            Map.entry("AGENT_TURN", "agent-turn"),
            Map.entry("IMG2THREE", "img2three"),
            Map.entry("EMAIL_REMINDER", "email-reminder"),
            Map.entry("CSDN_PUBLISH", "csdn-publish"),
            Map.entry("JUEJIN_PUBLISH", "juejin-publish"));

    private static Map<String, String> actualConstants() throws Exception {
        Map<String, String> found = new LinkedHashMap<>();
        for (Field field : BillingBiz.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            if (field.getType() != String.class) {
                continue;
            }
            found.put(field.getName(), (String) field.get(null));
        }
        return found;
    }

    /**
     * 词表必须与预期**完全一致** —— 多一项、少一项、改一个字母都失败。
     *
     * <p>失败信息会打印整个 map，所以看到 diff 就知道动了哪条。</p>
     */
    @Test
    void theCatalogueMatchesExactlyThePersistedBizTypeValues() throws Exception {
        assertEquals(EXPECTED, actualConstants());
    }

    /** 词表规模也要钉住：{@code Map} 比对已经覆盖，这里让"12 项"这个事实显式可见。 */
    @Test
    void theCatalogueHasTwelveEntries() throws Exception {
        assertEquals(12, actualConstants().size());
    }

    /**
     * 值必须两两不同。
     *
     * <p>两个业务项取同一个值，流水里就再也分不出是谁扣的费，而对账只会看到
     * "某一类消耗比预期多"。这条断言让那种复制粘贴错误当场失败。</p>
     */
    @Test
    void everyBizTypeValueIsDistinct() throws Exception {
        Map<String, String> actual = actualConstants();

        assertEquals(actual.size(), new HashSet<>(actual.values()).size(),
                "duplicate biz_type value in " + actual);
    }

    /**
     * 收入类业务项只有两个，且就是这两个。
     *
     * <p>这是一处**跨模块耦合**，必须留痕：{@code BillingService.isCreditBiz()}
     * 按 {@code GRANT_REGISTER} 与 {@code MERGE_CREDIT} 判断一笔流水是收入还是消耗
     * （决定余额是加还是减、{@code delta} 是正还是负）。新增一个"收入类"业务项时
     * 忘了同步那处判断，余额方向就会反 —— 而这是运行期才会暴露的错。
     * 这里把"哪两个是收入类"钉在契约侧，改动者至少会在测试名里看到这件事。</p>
     */
    @Test
    void exactlyTwoBizTypesAreCreditAndTheyArePinnedHere() {
        assertEquals("grant", BillingBiz.GRANT_REGISTER);
        assertEquals("merge-credit", BillingBiz.MERGE_CREDIT);

        // 这两个必须仍是词表成员：上面整体比对已覆盖，这里只是让意图可读。
        assertTrue(EXPECTED.containsValue(BillingBiz.GRANT_REGISTER));
        assertTrue(EXPECTED.containsValue(BillingBiz.MERGE_CREDIT));
    }

    /** 它是词表而不是可实例化的东西：final 类 + 私有构造器。 */
    @Test
    void theCatalogueIsFinalAndNotInstantiable() {
        assertTrue(Modifier.isFinal(BillingBiz.class.getModifiers()));
        assertThrows(ReflectiveOperationException.class,
                () -> BillingBiz.class.getDeclaredConstructor().newInstance());
    }

    /**
     * 它必须住在契约包里。
     *
     * <p>一旦有人把它挪回 {@code billing.domain}，六个域的调用点就又跨服务边界指回
     * 另一个服务的内部，而指标上只会看到数字变大、看不出方向错了 —— 所以钉住包名。</p>
     */
    @Test
    void theCatalogueStaysInTheContractPackage() {
        assertEquals("com.xander.lab.payment.api", BillingBiz.class.getPackageName());
    }
}
