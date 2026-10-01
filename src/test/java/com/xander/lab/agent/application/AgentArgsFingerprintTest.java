package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参数指纹的归一化规则，以及它**故意不合并**的那些差异。
 *
 * <p>这个类钉的每一组"相等"都对应一种曾经会产生的假差异（字段顺序、数字写法、时间写法），
 * 每一组"不等"都对应一个**不能合并**的真实差异。后者比前者重要：假差异只是多铸一个键，
 * 而错误合并会让用户的一个动作被下游去重静默吞掉。</p>
 *
 * <p>所以本类里最关键的用例是 {@link #differentMomentsInTheSameFieldDoNotAgree()}——
 * 它钉的是"归一化表示形式、而不是丢弃时间字段"这个决定。计划原本要求丢弃时间戳字段，
 * 那样这两个参数会哈希相同，于是一条被取消的提醒会让用户真正要发的另一条永远建不出来。</p>
 */
class AgentArgsFingerprintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException("测试里的 JSON 字面量不合法：" + text, exception);
        }
    }

    // ---------- 假差异：必须合并 ----------

    @Test
    void fieldOrderDoesNotChangeTheFingerprint() {
        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"a\":1,\"b\":2}")))
                .isEqualTo(AgentArgsFingerprint.of("publish_post", json("{\"b\":2,\"a\":1}")));
    }

    @Test
    void nestedFieldOrderDoesNotChangeTheFingerprint() {
        assertThat(AgentArgsFingerprint.of("schedule_plan",
                json("{\"outer\":{\"x\":1,\"y\":2},\"list\":[1,2]}")))
                .isEqualTo(AgentArgsFingerprint.of("schedule_plan",
                        json("{\"list\":[1,2],\"outer\":{\"y\":2,\"x\":1}}")));
    }

    @Test
    void nullBlankAndEmptyContainersAreEquivalentToOmission() {
        String expected = AgentArgsFingerprint.of("publish_post", json("{\"postId\":7}"));

        // 模型省略一个字段，与显式给 null / "" / [] / {} 表达的是同一件事。
        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7,\"platform\":null}")))
                .isEqualTo(expected);
        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7,\"platform\":\"\"}")))
                .isEqualTo(expected);
        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7,\"tags\":[]}")))
                .isEqualTo(expected);
        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7,\"extra\":{}}")))
                .isEqualTo(expected);
    }

    @Test
    void numericFormsOfTheSameValueAgree() {
        String expected = AgentArgsFingerprint.of("publish_post", json("{\"postId\":5}"));

        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"postId\":5.0}"))).isEqualTo(expected);
        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"postId\":5.00}"))).isEqualTo(expected);
    }

    @Test
    void trailingZerosAreStrippedWithoutSwitchingToScientificNotation() {
        // stripTrailingZeros() 会把 1000 变成 1E+3；若忘了 toPlainString，这里会得到 "1E+3"
        // 而 1000.00 会得到 "1E+3" 也一样——但 1000 与 1000.0 的写法差异必须消掉。
        assertThat(AgentArgsFingerprint.of("t", json("{\"n\":1000}")))
                .isEqualTo(AgentArgsFingerprint.of("t", json("{\"n\":1000.00}")));
        assertThat(AgentArgsFingerprint.of("t", json("{\"n\":1000}")))
                .isNotEqualTo(AgentArgsFingerprint.of("t", json("{\"n\":1}")));
    }

    @Test
    void momentSpellingsOfTheSameInstantAgree() {
        // 秒可省略、分隔符可空格可 T —— 这些是"同一时刻的不同写法"。
        String expected = AgentArgsFingerprint.of("email_reminder",
                json("{\"scheduledAt\":\"2026-09-26T09:00:00\"}"));

        assertThat(AgentArgsFingerprint.of("email_reminder",
                json("{\"scheduledAt\":\"2026-09-26 09:00:00\"}"))).isEqualTo(expected);
        assertThat(AgentArgsFingerprint.of("email_reminder",
                json("{\"scheduledAt\":\"2026-09-26T09:00\"}"))).isEqualTo(expected);
        assertThat(AgentArgsFingerprint.of("email_reminder",
                json("{\"scheduledAt\":\"2026-09-26 09:00\"}"))).isEqualTo(expected);
    }

    @Test
    void offsetMomentSpellingsAgreeButKeepTheirOffset() {
        String expected = AgentArgsFingerprint.of("email_reminder",
                json("{\"scheduledAt\":\"2026-09-26T09:00:00Z\"}"));

        assertThat(AgentArgsFingerprint.of("email_reminder",
                json("{\"scheduledAt\":\"2026-09-26T09:00Z\"}"))).isEqualTo(expected);
    }

    @Test
    void plainTextIsLeftAlone() {
        // 绝大多数参数是普通文本，解析失败是常态而不是异常路径。
        assertThat(AgentArgsFingerprint.of("t", json("{\"title\":\"今天写了点东西\"}")))
                .isEqualTo(AgentArgsFingerprint.of("t", json("{\"title\":\"今天写了点东西\"}")));
        assertThat(AgentArgsFingerprint.of("t", json("{\"title\":\"2026-09\"}")))
                .isNotEqualTo(AgentArgsFingerprint.of("t", json("{\"title\":\"2026-10\"}")));
    }

    @Test
    void nullArgumentsEqualAnEmptyObject() {
        assertThat(AgentArgsFingerprint.of("query_posts", null))
                .isEqualTo(AgentArgsFingerprint.of("query_posts", json("{}")));
    }

    // ---------- 真实差异：必须保留 ----------

    @Test
    void differentMomentsInTheSameFieldDoNotAgree() {
        // **本类最重要的一条。** 计划要求"时间戳字段不参与哈希"，若照做，这两个参数哈希相同，
        // 于是一条被取消的 09:00 提醒会让用户真正要发的 15:00 提醒被当成重试接上旧键，
        // 下游去重后那条 15:00 的提醒永远建不出来——静默吞掉用户的动作。
        assertThat(AgentArgsFingerprint.of("email_reminder",
                json("{\"scheduledAt\":\"2026-09-26T09:00\"}")))
                .isNotEqualTo(AgentArgsFingerprint.of("email_reminder",
                        json("{\"scheduledAt\":\"2026-09-27T15:00\"}")));
    }

    @Test
    void theSameLocalTimeInDifferentZonesIsNotTheSameMoment() {
        // 带偏移量与不带偏移量是两个不同的时刻（后者取决于用户时区），不能合并。
        assertThat(AgentArgsFingerprint.of("email_reminder",
                json("{\"scheduledAt\":\"2026-09-26T09:00:00Z\"}")))
                .isNotEqualTo(AgentArgsFingerprint.of("email_reminder",
                        json("{\"scheduledAt\":\"2026-09-26T09:00:00\"}")));
    }

    @Test
    void arrayOrderIsSignificant() {
        // 数组顺序是语义的一部分（用户列的清单顺序），不排序。
        assertThat(AgentArgsFingerprint.of("t", json("{\"tags\":[1,2]}")))
                .isNotEqualTo(AgentArgsFingerprint.of("t", json("{\"tags\":[2,1]}")));
    }

    @Test
    void arrayElementsAreNeverDropped() {
        // 对象里丢空字段是安全的（省略 == 空），数组里丢元素是**改变了参数本身**。
        assertThat(AgentArgsFingerprint.of("t", json("{\"tags\":[1,null]}")))
                .isNotEqualTo(AgentArgsFingerprint.of("t", json("{\"tags\":[1]}")));
        assertThat(AgentArgsFingerprint.of("t", json("{\"tags\":[1,\"\"]}")))
                .isNotEqualTo(AgentArgsFingerprint.of("t", json("{\"tags\":[1]}")));
    }

    @Test
    void differentToolNamesNeverAgree() {
        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7}")))
                .isNotEqualTo(AgentArgsFingerprint.of("query_posts", json("{\"postId\":7}")));
    }

    @Test
    void synonymsAreNotRewritten() {
        // 同义改写需要语义判断，而这里必须确定性——所以它**故意**产生两个指纹。
        // 这正是采纳判据不能只靠参数比对的原因（见 AgentEffectService 的类注释）。
        assertThat(AgentArgsFingerprint.of("t", json("{\"title\":\"发博客\"}")))
                .isNotEqualTo(AgentArgsFingerprint.of("t", json("{\"title\":\"生成博客\"}")));
    }

    @Test
    void quotesAndEscapesCannotCollide() {
        // quote() 是单射：{"a":"b,c"} 与 {"a":"b","c":""} 不能撞到一起。
        assertThat(AgentArgsFingerprint.of("t", json("{\"a\":\"b,c\"}")))
                .isNotEqualTo(AgentArgsFingerprint.of("t", json("{\"a\":\"b\",\"c\":\"\"}")));
        assertThat(AgentArgsFingerprint.of("t", json("{\"a\":\"x\\\"y\"}")))
                .isNotEqualTo(AgentArgsFingerprint.of("t", json("{\"a\":\"x\\\\y\"}")));
    }

    // ---------- 形状与旧算法兼容 ----------

    @Test
    void theFingerprintIsSixtyFourLowercaseHexCharacters() {
        // 必须与列定义 CHAR(64) 对齐，否则落库会被截断或补齐。
        assertThat(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7}")))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void theNewFingerprintMatchesItself() {
        JsonNode args = json("{\"postId\":7,\"platform\":\"csdn\"}");
        assertThat(AgentArgsFingerprint.matches(
                AgentArgsFingerprint.of("publish_post", args), "publish_post", args)).isTrue();
    }

    @Test
    void aFingerprintWrittenByTheLegacyAlgorithmStillMatches() {
        // 迁移前落库的待决审批用的是旧算法。不兼容它，一次部署会把所有挂在半空的审批作废，
        // 用户被锁在自己的会话外面——B2 修掉的那一类故障。
        JsonNode args = json("{\"postId\":7}");
        assertThat(AgentArgsFingerprint.matches(
                AgentArgsFingerprint.legacyOf("publish_post", args), "publish_post", args)).isTrue();
    }

    @Test
    void theLegacyAlgorithmIsFieldOrderSensitiveWhichIsWhyItWasReplaced() {
        // 这条用例把"旧算法为什么必须被换掉"钉成事实，而不是留在注释里。
        assertThat(AgentArgsFingerprint.legacyOf("t", json("{\"a\":1,\"b\":2}")))
                .isNotEqualTo(AgentArgsFingerprint.legacyOf("t", json("{\"b\":2,\"a\":1}")));
        // 而新算法对同一对输入必须相等。
        assertThat(AgentArgsFingerprint.of("t", json("{\"a\":1,\"b\":2}")))
                .isEqualTo(AgentArgsFingerprint.of("t", json("{\"b\":2,\"a\":1}")));
    }

    @Test
    void theLegacyBranchDoesNotWeakenRealDriftDetection() {
        // 兼容分支不能让"参数真的变了"被放过：两种算法都会算出不同的值。
        JsonNode approved = json("{\"postId\":7}");
        JsonNode drifted = json("{\"postId\":8}");
        assertThat(AgentArgsFingerprint.matches(
                AgentArgsFingerprint.legacyOf("publish_post", approved), "publish_post", drifted)).isFalse();
        assertThat(AgentArgsFingerprint.matches(
                AgentArgsFingerprint.of("publish_post", approved), "publish_post", drifted)).isFalse();
    }

    @Test
    void aBlankStoredFingerprintNeverMatches() {
        // 空指纹意味着"这一行没有记录承诺"，不能当成"一致"而放行。
        JsonNode args = json("{\"postId\":7}");
        assertThat(AgentArgsFingerprint.matches(null, "publish_post", args)).isFalse();
        assertThat(AgentArgsFingerprint.matches("", "publish_post", args)).isFalse();
        assertThat(AgentArgsFingerprint.matches("   ", "publish_post", args)).isFalse();
    }
}
