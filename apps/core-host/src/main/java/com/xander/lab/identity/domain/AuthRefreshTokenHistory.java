package com.xander.lab.identity.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌轮换历史（有界 Replay History）。
 *
 * <p>每一次成功轮换都在这里留一行，记录"哪个 token 被消费了、后继是谁、
 * 幂等窗口何时结束、还能识别盗用到什么时候"。四个 Reuse Detection 分支里
 * 有三分支依赖这张表。</p>
 *
 * <h3>三个窗口互相独立，不得推导</h3>
 * <ul>
 *   <li>{@code graceExpiresAt} —— 幂等返回窗口（秒级，默认 25s），决定"重放是否算 theft"</li>
 *   <li>{@code retainedUntil} —— Reuse 判定窗口（小时级，默认 24h），决定"还能不能识别为 theft"</li>
 *   <li>{@code successorCiphertextExpiresAt} —— 密文物理清理窗口（秒级）</li>
 * </ul>
 *
 * <p>只保留 grace 会导致 25 秒之后的真实盗用无法被识别。</p>
 *
 * <h3>密文清理语义</h3>
 * <p>代码层：{@code now > successorCiphertextExpiresAt} 之后禁止解密/返回。<br>
 * 物理层：异步任务在 grace 结束后清空 ciphertext。</p>
 * <p>两者解耦，所以不会出现"成功读取后提前清空导致第 3 次 replay 无法返回"。</p>
 */
@Data
@TableName("auth_refresh_token_history")
public class AuthRefreshTokenHistory {

    /** 被消费掉的 refresh token 的 tokenId */
    @TableId(value = "token_id", type = IdType.INPUT)
    private String tokenId;

    /** 所属刷新会话 */
    private String sid;

    /** 被消费 token secret 的哈希 */
    private String tokenHash;

    /** 被消费 token 的轮换代次 */
    private Integer generation;

    /** 该 token 被成功消费的时间 */
    private LocalDateTime usedAt;

    /** 轮换产生的后继 tokenId */
    private String successorTokenId;

    /** 幂等返回窗口截止时间 */
    private LocalDateTime graceExpiresAt;

    /**
     * Reuse 判定窗口截止时间。
     *
     * <p>写入规则：RT(n) 轮换成功写 history 行时，
     * {@code retained_until = now() + retention}（即 now() + 24h），
     * <b>不得留空、不得依赖后续任务回填</b>。因此列定义为 NOT NULL 且无默认值，
     * 强制写入方显式提供。</p>
     */
    private LocalDateTime retainedUntil;

    /** 判定为真实 Reuse 的时间；非空表示该行已被识别为盗用 */
    private LocalDateTime reuseDetectedAt;

    /**
     * successor refresh token 的密文（AES/GCM）。
     *
     * <p>逻辑过期由 {@link #successorCiphertextExpiresAt} 判断；物理清空由
     * {@code auth-refresh-ciphertext-cleanup} 任务负责。</p>
     */
    private String successorTokenCiphertext;

    /** 密文逻辑过期时间；now 超过它之后禁止解密/返回 */
    private LocalDateTime successorCiphertextExpiresAt;

    /** 由 MySQL 维护 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 幂等窗口内是否可返回后继 token */
    public boolean isWithinGrace(LocalDateTime now) {
        return graceExpiresAt != null && !now.isAfter(graceExpiresAt);
    }

    /** 是否仍处于可识别 theft 的保留窗口内 */
    public boolean isWithinRetention(LocalDateTime now) {
        return retainedUntil != null && !now.isAfter(retainedUntil);
    }

    /** 密文是否仍可解密（逻辑层判断，与物理是否已清空无关） */
    public boolean isCiphertextReadable(LocalDateTime now) {
        return successorTokenCiphertext != null
                && !successorTokenCiphertext.isBlank()
                && successorCiphertextExpiresAt != null
                && !now.isAfter(successorCiphertextExpiresAt);
    }
}
