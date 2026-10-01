package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 工具名与参数的归一化指纹。
 *
 * <p><b>它回答的问题是"这两次调用说的是不是同一件事"</b>，只用于两处：审批的"变更操作必须重新审批"，
 * 与 Effect 的漂移检测告警。它**不是身份**——身份是 {@code effect_id}。把参数当身份会让两种情况
 * 都判错：模型把同一件事换个说法（同义改写）会被当成新动作，而合法的内容变更会被当成重试。</p>
 *
 * <p><b>为什么必须共享一份实现。</b>以前它只是 {@code AgentApprovalService} 里的一个私有方法，
 * 用 {@code arguments.toString()} 直接哈希。如果 Effect 那边再抄一份，两处的归一化规则迟早分叉，
 * 于是"同一个动作"在审批眼里是同一个、在 Effect 眼里是两个——而两个指纹都没有任何交叉校验能发现这件事。
 * 所以审批与 Effect 都调这里。</p>
 *
 * <p><b>归一化规则</b>（每一条都对应一种真实的假差异）：</p>
 * <ul>
 *   <li>对象的 key **递归按字典序**排序：JSON 字段顺序无语义，但 {@code toString()} 顺序敏感，
 *       两个逻辑相同、key 顺序不同的参数会算出不同指纹。</li>
 *   <li>丢弃 {@code null}、空白字符串、空数组、空对象字段：模型省略一个字段与显式给空值，
 *       表达的是同一件事。</li>
 *   <li>数字统一 {@link BigDecimal#stripTrailingZeros()} 后取 {@code toPlainString()}：
 *       {@code 5}、{@code 5.0}、{@code 5.00} 是同一个数。</li>
 *   <li>**日期时间字符串归一化表示形式**（见下）。</li>
 *   <li>不做同义改写：{@code "发博客"} 与 {@code "生成博客"} 仍然是两个指纹。那需要语义判断，
 *       而这里必须是确定性的。</li>
 * </ul>
 *
 * <p><b>关于日期时间，这里偏离了计划。</b>计划写的是"时间戳类字段（{@code scheduledAt} 等）
 * 不参与哈希"。**不采用**：丢掉这个字段会让 {@code {"scheduledAt":"2026-09-26T09:00"}} 与
 * {@code {"scheduledAt":"2026-09-27T15:00"}} 哈希相同，而采纳只要求"存在恰好一个 OPEN 的 Effect"——
 * 于是一条被取消的 09:00 提醒会让用户真的要发的 15:00 提醒被当成重试，下游按 09:00 的键去重，
 * **那条 15:00 的提醒就永远建不出来**。那不是误报，是静默吞掉用户的动作。</p>
 *
 * <p>计划想解决的真实问题其实是**表示形式不稳定**（{@code "2026-09-26 09:00"} 与
 * {@code "2026-09-26T09:00:00"} 是同一时刻），所以这里改成**归一化形式而不是丢弃字段**：
 * 能解析成日期时间的字符串统一成 {@code yyyy-MM-dd'T'HH:mm:ss}（带时区的补上偏移量），
 * 解析不了就原样保留。这样既消除了形式漂移，又不把两个不同的时刻合并。</p>
 *
 * <p>解析失败是**常态**而不是异常路径（绝大多数参数就是普通文本），所以只在"看起来像日期时间"
 * 时才尝试解析，且失败时静默回退到原文本。</p>
 */
final class AgentArgsFingerprint {

    /** 空参数的标准形式：省略参数与显式给 {@code {}} 是同一件事。 */
    private static final String EMPTY = "{}";

    /**
     * 日期时间的可接受写法，按"信息量从多到少"排序。
     *
     * <p>{@code ISO_OFFSET_DATE_TIME} 与 {@code ISO_LOCAL_DATE_TIME} 覆盖标准写法；
     * 后两个模式覆盖"用空格分隔"这种人手写/模型爱写的变体，且秒可省略。</p>
     */
    private static final List<DateTimeFormatter> MOMENT_PARSERS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]"));

    /** 带偏移量的归一化形式。 */
    private static final DateTimeFormatter OFFSET_MOMENT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** 不带偏移量的归一化形式。秒固定补齐，否则 {@code 09:00} 与 {@code 09:00:00} 会分叉。 */
    private static final DateTimeFormatter LOCAL_MOMENT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private AgentArgsFingerprint() {
    }

    /**
     * 算出工具名与参数的归一化 SHA-256（64 位小写十六进制，与 {@code CHAR(64)} 对齐）。
     *
     * @param toolName  注册工具名；工具名不同则一定不是同一个动作
     * @param arguments 模型给的参数；{@code null} 与 {@code {}} 等价
     */
    static String of(String toolName, JsonNode arguments) {
        String canonical = render(arguments);
        String material = (toolName == null ? "" : toolName) + "\n" + (canonical == null ? EMPTY : canonical);
        return sha256Hex(material);
    }

    /**
     * 落库的指纹是否与当前参数一致。
     *
     * <p>**先比新算法，再比旧算法**，两者任一命中即算一致。第二个分支是给迁移前落库的行用的，
     * 不是冗余：</p>
     *
     * <ul>
     *   <li>旧算法是 {@code arguments.toString()}（字段顺序敏感）。切换算法之后，所有**当时还挂着**
     *       的待决审批，其 {@code request_fingerprint} 都是旧算法算的，拿新算法去比必然不等。</li>
     *   <li>而"不一致"的处置是**拒绝并作废这一轮**。于是一次部署会把恰好挂在半空的审批全部作废，
     *       用户被锁在那个会话里——正是 B2 修掉的那一类故障。</li>
     *   <li>兼容分支不会放宽真正的漂移判定：参数若真变了，两种算法都会算出不同的值。反过来，
     *       旧算法命中说明参数**逐字节相同**，那它当然也满足新算法的语义相同。</li>
     * </ul>
     *
     * <p>**移除条件**：当 {@code agent_approval} 里不再存在用旧算法落库的 PENDING 行之后，
     * 这个分支与 {@link #legacyOf} 一起删掉。</p>
     */
    static boolean matches(String storedFingerprint, String toolName, JsonNode arguments) {
        if (storedFingerprint == null || storedFingerprint.isBlank()) return false;
        if (storedFingerprint.equals(of(toolName, arguments))) return true;
        return storedFingerprint.equals(legacyOf(toolName, arguments));
    }

    /**
     * 迁移前的指纹算法：{@code arguments.toString()} 直接哈希。
     *
     * <p>保留它**只为读取旧数据**。新写入一律走 {@link #of}——那个的归一化规则与 Effect 的漂移检测
     * 共用一份，否则两边的规则迟早分叉，而两个指纹之间没有任何交叉校验能发现这件事。</p>
     *
     * <p>它的缺陷正是当初要换掉它的原因：字段顺序敏感。两个逻辑相同、key 顺序不同的参数会算出
     * 不同指纹——对审批是安全方向（过度要求重新审批），用作漂移检测则会产生大量误报。</p>
     */
    static String legacyOf(String toolName, JsonNode arguments) {
        return sha256Hex((toolName == null ? "" : toolName) + "\n"
                + (arguments == null ? EMPTY : arguments.toString()));
    }

    /**
     * 把任意 JSON 渲染成确定性的规范文本。
     *
     * @return {@code null} 表示"这个值等同于不存在"（null / 空白串 / 空容器）
     */
    private static String render(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            // 字典序排序：JSON 字段顺序无语义，但哈希对顺序敏感。
            Collections.sort(names);
            List<String> parts = new ArrayList<>(names.size());
            for (String name : names) {
                String rendered = render(node.get(name));
                if (rendered == null) continue;
                parts.add(quote(name) + ":" + rendered);
            }
            return parts.isEmpty() ? null : "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) {
            if (node.isEmpty()) return null;
            List<String> parts = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                String rendered = render(element);
                // 数组元素的顺序是语义的一部分（用户列的清单），所以这里**不排序、不丢弃**：
                // 丢一个元素就改变了参数本身。渲染不出内容时显式写 null 占位。
                parts.add(rendered == null ? "null" : rendered);
            }
            return "[" + String.join(",", parts) + "]";
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (text.isBlank()) return null;
            String moment = canonicalMoment(text);
            return quote(moment == null ? text : moment);
        }
        if (node.isNumber()) return plain(node.decimalValue());
        if (node.isBoolean()) return node.asBoolean() ? "true" : "false";
        return quote(node.asText());
    }

    /** 数字的唯一形式：{@code 5}、{@code 5.0}、{@code 5.00} 必须落到同一个文本。 */
    private static String plain(BigDecimal value) {
        // stripTrailingZeros 会把 1000 变成 1E+3，toPlainString 再把它摊平回 1000。
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * 把"看起来像日期时间"的字符串归一化成固定形式；不像就返回 {@code null} 让调用方保留原文本。
     *
     * <p>归一化**保留时区信息**：{@code 2026-09-26T09:00:00Z} 与
     * {@code 2026-09-26T09:00:00} 是两个不同的时刻，不该合并。</p>
     */
    private static String canonicalMoment(String text) {
        String trimmed = text.trim();
        // 快速排除：日期时间至少要有 "yyyy-MM-dd" 这么长，且第 4、7 位是分隔符。
        if (trimmed.length() < 10 || trimmed.charAt(4) != '-' || trimmed.charAt(7) != '-') return null;
        for (DateTimeFormatter parser : MOMENT_PARSERS) {
            try {
                var parsed = parser.parse(trimmed);
                OffsetDateTime offset = parsed.isSupported(java.time.temporal.ChronoField.OFFSET_SECONDS)
                        ? OffsetDateTime.from(parsed) : null;
                if (offset != null) {
                    return OFFSET_MOMENT.format(offset.withNano(0));
                }
                return LOCAL_MOMENT.format(LocalDateTime.from(parsed).withNano(0));
            } catch (DateTimeException ignored) {
                // 换下一个模式。解析失败、以及"解析成功但取不出所需字段"（OffsetDateTime.from /
                // LocalDateTime.from 也会抛 DateTimeException）都在这里收敛。
                // 全部模式失败说明它不是日期时间，交给调用方保留原文本。
            }
        }
        return null;
    }

    /**
     * 字符串的注入式编码：转义反斜杠、引号与控制字符。
     *
     * <p>目的是让编码**单射**（两个不同字符串必得不同文本），而不是产出合法 JSON——
     * 这个结果只会被喂给哈希，不会被解析。</p>
     */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (current < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        out.append(current);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String sha256Hex(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value));
            return text.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
