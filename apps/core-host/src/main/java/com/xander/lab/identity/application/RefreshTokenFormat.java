package com.xander.lab.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Refresh Token 的格式与哈希。
 *
 * <p>格式：{@code rt_<sid>.<tokenId>.<secret>} —— <b>Opaque Random Token</b>，
 * 不是 JWT。数据库长期只保存 secret 的哈希，不长期存明文。</p>
 *
 * <h3>安全提示</h3>
 * <p>{@code sid} 位于<b>明文前缀</b>，因此 <b>sid 不是秘密</b>。任何拿到过一次
 * token 的地方（日志、Referer、客户端存储）都会泄漏 sid。这条事实直接决定了
 * Reuse Detection 判定表必须包含"垃圾 token 不撤销会话族"的分支——
 * 否则攻击者构造 {@code rt_<已知sid>.<随机>.<随机>} 反复提交即可让用户被反复踢下线。</p>
 *
 * <h3>为什么哈希带上 tokenId</h3>
 * <p>{@code token_hash = SHA-256(tokenId + ":" + secret)}，而不是只哈希 secret。
 * tokenId 本身不是秘密（它也要参与查表），加上它做域分隔可以避免"同一 secret
 * 出现在不同 tokenId 下"时被误判为同一 token。256 位随机 secret 让碰撞在实际中
 * 不可能发生，域分隔是防御性写法。</p>
 */
public final class RefreshTokenFormat {

    /** Token 前缀，便于在日志 / 抓包里一眼识别类型。 */
    public static final String PREFIX = "rt_";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private RefreshTokenFormat() {
    }

    /** 解析结果：明文三元组。 */
    public record Parsed(String sid, String tokenId, String secret) {
    }

    /** 生成 256 位随机 secret（Base64URL，无填充，43 字符）。 */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /** 生成 128 位随机标识（sid / tokenId / familyId 共用）。 */
    public static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /** 拼装对外下发的 refresh token 明文。 */
    public static String format(String sid, String tokenId, String secret) {
        return PREFIX + sid + "." + tokenId + "." + secret;
    }

    /**
     * 解析客户端提交的 refresh token。
     *
     * <p>任何形状不符的输入都返回空 —— 这是"垃圾 token"分支的入口，
     * 调用方必须把它当成 {@code 401 INVALID_REFRESH_TOKEN} 而不是 theft。</p>
     */
    public static Optional<Parsed> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String body = raw.startsWith(PREFIX) ? raw.substring(PREFIX.length()) : raw;
        String[] parts = body.split("\\.", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        if (parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(parts[0], parts[1], parts[2]));
    }

    /**
     * 计算落库用的 token 哈希。
     *
     * <p>不使用 BCrypt：这是高熵随机串（256 位），不是用户口令，SHA-256 足够，
     * 而且 refresh 是热路径，需要低成本比较。</p>
     */
    public static String hash(String tokenId, String secret) {
        return sha256Hex(tokenId + ":" + secret);
    }

    /** 恒定时间比较，避免通过响应时间侧信道逐字节猜测哈希。 */
    public static boolean hashMatches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
