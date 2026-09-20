package com.xander.lab.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新会话（Refresh Session SOT）。
 *
 * <p>一个登录会话一行。轮换时**原地修改** {@code currentTokenId} /
 * {@code currentTokenHash} / {@code generation}，因此这一行是唯一知道
 * "当前哪个 refresh token 有效"的地方。</p>
 *
 * <p>它取代了旧的纯无状态 refresh JWT —— 旧模型只验签名 + {@code type} +
 * 用户 status，不查任何存储，所以 logout 无法吊销，登出后 30 天内仍可换出
 * 全新令牌对。</p>
 *
 * <p>注意：{@code sid} 位于 refresh token 的明文前缀 {@code rt_<sid>.<tokenId>.<secret>}，
 * 所以它不是秘密 —— 日志 / Referer / 客户端存储任一处泄漏即可获得。这条事实
 * 直接决定了 Reuse Detection 必须有"垃圾 token 不撤销会话族"的分支。</p>
 */
@Data
@TableName("auth_refresh_session")
public class AuthRefreshSession {

    /** 会话状态：有效 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 会话状态：已撤销 */
    public static final String STATUS_REVOKED = "REVOKED";
    /** 会话状态：已过期 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 撤销原因：用户登出当前设备 */
    public static final String REASON_LOGOUT = "LOGOUT";
    /** 撤销原因：登出全部设备 */
    public static final String REASON_LOGOUT_ALL = "LOGOUT_ALL";
    /** 撤销原因：管理员封禁 */
    public static final String REASON_ADMIN_DISABLE = "ADMIN_DISABLE";
    /** 撤销原因：检测到 refresh token 重用 */
    public static final String REASON_REUSE_DETECTED = "REUSE_DETECTED";
    /** 撤销原因：修改密码 */
    public static final String REASON_PASSWORD_CHANGED = "PASSWORD_CHANGED";

    /** 会话标识；同时是 refresh token 的明文前缀，因此不是秘密 */
    @TableId(value = "sid", type = IdType.INPUT)
    private String sid;

    /** 所属账号 ID，关联 sys_user.id */
    private Long accountId;

    /** 会话族标识；Reuse Detection 命中时按此撤销整族 */
    private String familyId;

    /** 当前有效 refresh token 的 tokenId */
    private String currentTokenId;

    /** 当前 refresh token secret 的哈希；明文永不落库 */
    private String currentTokenHash;

    /** 轮换代次；每次成功轮换 +1 */
    private Integer generation;

    /** ACTIVE / REVOKED / EXPIRED */
    private String status;

    private LocalDateTime issuedAt;

    private LocalDateTime lastRotatedAt;

    /** 会话绝对过期时间；轮换不延长该时间 */
    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private String revokedReason;

    /** 由 MySQL 维护，应用不写入（见 db/mysql_managed_audit_timestamps_migration.sql） */
    @TableField(value = "created_at", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    /** 会话当前是否可用于轮换 */
    public boolean isRotatable(LocalDateTime now) {
        return STATUS_ACTIVE.equals(status) && expiresAt != null && expiresAt.isAfter(now);
    }
}
