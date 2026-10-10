import type { D1Binding } from "@dinqor/worker-runtime";
import type { IdentityAccountRepository } from "../interfaces/identity-account-repository.js";
import type { IdentityProfileRepository } from "../interfaces/identity-profile-repository.js";
import type {
  IdentitySessionRepository,
  RefreshRotationWrite,
  RefreshSessionRecord,
  RefreshTokenHistoryRecord,
} from "../interfaces/identity-session-repository.js";
import type { IdentitySocialRepository } from "../interfaces/identity-social-repository.js";
import type { WechatCredentialRepository } from "../interfaces/wechat-credential-repository.js";
import type { IdentityRepository, IdentityUser } from "../interfaces/identity-repository.js";

const ACTIVE_USER_FOR_TOKEN = [
  "SELECT id, username, nickname, email, avatar, openid, role, tier,",
  "tier_expires_at AS tierExpiresAt, status, created_at AS createdAt,",
  "auth_version AS authVersion",
  "FROM sys_user WHERE id = ? AND NOT EXISTS (",
  "SELECT 1 FROM auth_token_revocation r",
  "WHERE r.token_hash = ? AND r.expires_at > ?)",
].join(" ");

export class D1IdentityMapper implements
  IdentityRepository,
  IdentityAccountRepository,
  IdentityProfileRepository,
  IdentitySessionRepository,
  IdentitySocialRepository,
  WechatCredentialRepository {
  constructor(private readonly db: D1Binding) {}

  findActiveUserForToken(
    userId: number,
    tokenFingerprint: string,
    now: number,
  ): Promise<IdentityUser | null> {
    return this.db.prepare(ACTIVE_USER_FOR_TOKEN)
      .bind(userId, tokenFingerprint, now)
      .first<IdentityUser>();
  }

  findByEmailOrUsername(email: string): Promise<IdentityUser | null> {
    return this.db.prepare(`
      SELECT id, username, nickname, email, avatar, openid, role, tier,
             tier_expires_at AS tierExpiresAt, status, created_at AS createdAt,
             auth_version AS authVersion
      FROM sys_user WHERE email = ? COLLATE NOCASE OR username = ? COLLATE NOCASE
      LIMIT 1
    `).bind(email, email).first<IdentityUser>();
  }

  findForPasswordLogin(account: string): Promise<IdentityUser | null> {
    return this.db.prepare(`
      SELECT id, username, nickname, email, avatar, role, tier,
             tier_expires_at AS tierExpiresAt, status, created_at AS createdAt,
             auth_version AS authVersion, password
      FROM sys_user
      WHERE username = ? COLLATE NOCASE OR email = ? COLLATE NOCASE
      LIMIT 1
    `).bind(account, account).first<IdentityUser>();
  }

  createAccount(
    email: string,
    passwordHash: string,
    nickname: string,
    avatar: string,
  ): Promise<IdentityUser | null> {
    return this.db.prepare(`
      INSERT INTO sys_user (
        username, password, nickname, email, avatar, role, status, registration_grant_pending
      )
      VALUES (?, ?, ?, ?, ?, 'USER', 1, 1)
      ON CONFLICT DO NOTHING
      RETURNING id, username, nickname, email, avatar, role, tier,
                tier_expires_at AS tierExpiresAt, status, created_at AS createdAt,
                auth_version AS authVersion
    `).bind(email, passwordHash, nickname, email, avatar).first<IdentityUser>();
  }

  async findActivePassword(userId: number): Promise<string | null> {
    const row = await this.db.prepare("SELECT password FROM sys_user WHERE id = ? AND status = 1")
      .bind(userId)
      .first<{ password: string | null }>();
    return row?.password ?? null;
  }

  async updatePasswordForActiveUser(userId: number, passwordHash: string): Promise<void> {
    const now = new Date().toISOString();
    await this.db.batch([
      this.db.prepare(`
        UPDATE sys_user
        SET password = ?, auth_version = auth_version + 1,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND status = 1
      `).bind(passwordHash, userId),
      this.db.prepare(`
        UPDATE auth_refresh_session
        SET status = 'REVOKED', revoked_at = ?, revoked_reason = 'PASSWORD_CHANGED',
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE account_id = ? AND status = 'ACTIVE'
      `).bind(now, userId),
    ]);
  }

  async findUsernameOwner(username: string, exceptUserId: number): Promise<number | null> {
    const row = await this.db.prepare(
      "SELECT id FROM sys_user WHERE username = ? COLLATE NOCASE AND id <> ? LIMIT 1",
    ).bind(username, exceptUserId).first<{ id: number }>();
    return row?.id ?? null;
  }

  async updateProfile(
    userId: number,
    username: string,
    nickname: string,
    avatar: string | null,
  ): Promise<boolean> {
    const result = await this.db.prepare(
      "UPDATE sys_user SET username = ?, nickname = ?, avatar = ?, "
        + "updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') "
        + "WHERE id = ? AND status = 1",
    ).bind(username, nickname, avatar, userId).run();
    return result.meta.changes === 1;
  }

  findActiveUserById(userId: number): Promise<IdentityUser | null> {
    return this.db.prepare(`
      SELECT id, username, nickname, email, avatar, openid, role, tier,
             tier_expires_at AS tierExpiresAt, status, created_at AS createdAt,
             auth_version AS authVersion
      FROM sys_user WHERE id = ? AND status = 1
    `).bind(userId).first<IdentityUser>();
  }

  async recordTokenRevocation(
    tokenFingerprint: string,
    expiresAt: number,
    revokedAt: number,
  ): Promise<void> {
    await this.db.prepare(`
      INSERT INTO auth_token_revocation (token_hash, expires_at, revoked_at)
      VALUES (?, ?, ?)
      ON CONFLICT(token_hash) DO UPDATE SET
        expires_at = MAX(auth_token_revocation.expires_at, excluded.expires_at),
        revoked_at = MAX(auth_token_revocation.revoked_at, excluded.revoked_at)
    `).bind(tokenFingerprint, expiresAt, revokedAt).run();
  }

  async deleteExpiredTokenRevocations(now: number): Promise<void> {
    await this.db.prepare("DELETE FROM auth_token_revocation WHERE expires_at <= ?")
      .bind(now)
      .run();
  }

  async createRefreshSession(session: RefreshSessionRecord): Promise<void> {
    await this.db.prepare(`
      INSERT INTO auth_refresh_session (
        sid, account_id, family_id, current_token_id, current_token_hash, generation,
        status, issued_at, last_rotated_at, expires_at, revoked_at, revoked_reason
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      session.sid, session.accountId, session.familyId, session.currentTokenId,
      session.currentTokenHash, session.generation, session.status, session.issuedAt,
      session.lastRotatedAt, session.expiresAt, session.revokedAt, session.revokedReason,
    ).run();
  }

  findRefreshSession(sid: string): Promise<RefreshSessionRecord | null> {
    return this.db.prepare(`
      SELECT sid, account_id AS accountId, family_id AS familyId,
             current_token_id AS currentTokenId, current_token_hash AS currentTokenHash,
             generation, status, issued_at AS issuedAt, last_rotated_at AS lastRotatedAt,
             expires_at AS expiresAt, revoked_at AS revokedAt, revoked_reason AS revokedReason
      FROM auth_refresh_session WHERE sid = ? LIMIT 1
    `).bind(sid).first<RefreshSessionRecord>();
  }

  findRefreshTokenHistory(tokenId: string): Promise<RefreshTokenHistoryRecord | null> {
    return this.db.prepare(`
      SELECT token_id AS tokenId, sid, token_hash AS tokenHash, generation,
             used_at AS usedAt, successor_token_id AS successorTokenId,
             grace_expires_at AS graceExpiresAt, retained_until AS retainedUntil,
             reuse_detected_at AS reuseDetectedAt,
             successor_token_ciphertext AS successorTokenCiphertext,
             successor_ciphertext_expires_at AS successorCiphertextExpiresAt
      FROM auth_refresh_token_history WHERE token_id = ? LIMIT 1
    `).bind(tokenId).first<RefreshTokenHistoryRecord>();
  }

  async rotateRefreshSession(write: RefreshRotationWrite): Promise<boolean> {
    const session = write.session;
    const results = await this.db.batch([
      this.db.prepare(`
        UPDATE auth_refresh_session
        SET current_token_id = ?, current_token_hash = ?, generation = ?, last_rotated_at = ?,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE sid = ? AND status = 'ACTIVE' AND expires_at > ?
          AND current_token_id = ? AND current_token_hash = ? AND generation = ?
      `).bind(
        session.currentTokenId, session.currentTokenHash, session.generation,
        session.lastRotatedAt, session.sid, write.usedAt, write.previousTokenId,
        write.previousTokenHash, write.previousGeneration,
      ),
      this.db.prepare(`
        INSERT INTO auth_refresh_token_history (
          token_id, sid, token_hash, generation, used_at, successor_token_id,
          grace_expires_at, retained_until, successor_token_ciphertext,
          successor_ciphertext_expires_at
        )
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        WHERE EXISTS (
          SELECT 1 FROM auth_refresh_session
          WHERE sid = ? AND current_token_id = ? AND generation = ?
        )
      `).bind(
        write.previousTokenId, session.sid, write.previousTokenHash, write.previousGeneration,
        write.usedAt, write.successorTokenId, write.graceExpiresAt, write.retainedUntil,
        write.successorTokenCiphertext, write.graceExpiresAt,
        session.sid, session.currentTokenId, session.generation,
      ),
      this.db.prepare(`
        DELETE FROM auth_refresh_token_history
        WHERE sid = ? AND generation < ?
      `).bind(session.sid, session.generation - 48),
    ]);
    return results[0]?.meta.changes === 1 && results[1]?.meta.changes === 1;
  }

  async revokeRefreshFamily(sid: string, tokenId: string, occurredAt: string): Promise<void> {
    await this.db.batch([
      this.db.prepare(`
        UPDATE auth_refresh_session
        SET status = 'REVOKED', revoked_at = ?, revoked_reason = 'REUSE_DETECTED',
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE family_id = (SELECT family_id FROM auth_refresh_session WHERE sid = ?)
          AND status = 'ACTIVE'
      `).bind(occurredAt, sid),
      this.db.prepare(`
        UPDATE auth_refresh_token_history SET reuse_detected_at = ?
        WHERE token_id = ? AND sid = ? AND reuse_detected_at IS NULL
      `).bind(occurredAt, tokenId, sid),
      this.db.prepare(`
        INSERT INTO auth_refresh_reuse_event (account_id, sid, token_id, occurred_at)
        SELECT account_id, sid, ?, ? FROM auth_refresh_session WHERE sid = ?
        ON CONFLICT(token_id) DO NOTHING
      `).bind(tokenId, occurredAt, sid),
    ]);
  }

  async revokeRefreshSession(sid: string, reason: string, revokedAt: string): Promise<void> {
    await this.db.prepare(`
      UPDATE auth_refresh_session
      SET status = 'REVOKED', revoked_at = ?, revoked_reason = ?,
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE sid = ? AND status = 'ACTIVE'
    `).bind(revokedAt, reason, sid).run();
  }

  async revokeRefreshSessionsForAccount(accountId: number, reason: string, revokedAt: string): Promise<void> {
    await this.db.prepare(`
      UPDATE auth_refresh_session
      SET status = 'REVOKED', revoked_at = ?, revoked_reason = ?,
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE account_id = ? AND status = 'ACTIVE'
    `).bind(revokedAt, reason, accountId).run();
  }

  async cleanupRefreshData(now: string, batchSize: number): Promise<void> {
    await this.db.batch([
      this.db.prepare(`
        UPDATE auth_refresh_token_history SET successor_token_ciphertext = NULL
        WHERE token_id IN (
          SELECT token_id FROM auth_refresh_token_history
          WHERE successor_ciphertext_expires_at < ? AND successor_token_ciphertext IS NOT NULL
          ORDER BY successor_ciphertext_expires_at LIMIT ?
        )
      `).bind(now, batchSize),
      this.db.prepare(`
        DELETE FROM auth_refresh_token_history
        WHERE token_id IN (
          SELECT token_id FROM auth_refresh_token_history
          WHERE retained_until < ? ORDER BY retained_until LIMIT ?
        )
      `).bind(now, batchSize),
      this.db.prepare(`
        UPDATE auth_refresh_session
        SET status = 'EXPIRED', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE sid IN (
          SELECT sid FROM auth_refresh_session
          WHERE status = 'ACTIVE' AND expires_at <= ? ORDER BY expires_at LIMIT ?
        )
      `).bind(now, batchSize),
    ]);
  }

  findByOpenId(openid: string): Promise<IdentityUser | null> {
    return this.db.prepare(`
      SELECT id, username, nickname, email, avatar, openid, role, tier,
             tier_expires_at AS tierExpiresAt, status, created_at AS createdAt,
             auth_version AS authVersion
      FROM sys_user WHERE openid = ? LIMIT 1
    `).bind(openid).first<IdentityUser>();
  }

  createWechatUser(
    username: string,
    passwordHash: string,
    nickname: string,
    email: string | null,
    avatar: string,
    openid: string,
  ): Promise<IdentityUser | null> {
    return this.db.prepare(`
      INSERT INTO sys_user (
        username, password, nickname, email, avatar, openid, role, status, registration_grant_pending
      ) VALUES (?, ?, ?, ?, ?, ?, 'USER', 1, 1)
      ON CONFLICT DO NOTHING
      RETURNING id, username, nickname, email, avatar, role, tier,
                tier_expires_at AS tierExpiresAt, status, created_at AS createdAt,
                auth_version AS authVersion
    `).bind(username, passwordHash, nickname, email, avatar, openid).first<IdentityUser>();
  }

  async attachOpenIdIfEmpty(openid: string, userId: number): Promise<void> {
    await this.db.prepare(`
      UPDATE sys_user SET openid = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND openid IS NULL
    `).bind(openid, userId).run();
  }

  async attachEmailIfAvailable(email: string, userId: number): Promise<void> {
    await this.db.prepare(`
      UPDATE sys_user SET email = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND (email IS NULL OR email = ?)
    `).bind(email, userId, email).run();
  }

  async attachEmailToActiveUnboundUser(email: string, userId: number): Promise<void> {
    await this.db.prepare(`
      UPDATE sys_user SET email = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND status = 1 AND email IS NULL
    `).bind(email, userId).run();
  }

  findById(userId: number): Promise<IdentityUser | null> {
    return this.db.prepare(`
      SELECT id, username, nickname, email, avatar, openid, role, tier,
             tier_expires_at AS tierExpiresAt, status, created_at AS createdAt,
             auth_version AS authVersion
      FROM sys_user WHERE id = ? LIMIT 1
    `).bind(userId).first<IdentityUser>();
  }

  findWechatCredential(): Promise<{ appId: string; encryptedAppSecret: string; updatedAt: string } | null> {
    return this.db.prepare(`
      SELECT app_id AS appId, encrypted_app_secret AS encryptedAppSecret,
             updated_at AS updatedAt
      FROM wechat_credential WHERE id = 1
    `).first<{ appId: string; encryptedAppSecret: string; updatedAt: string }>();
  }

  saveWechatCredential(
    appId: string,
    encryptedAppSecret: string,
  ): Promise<{ appId: string; encryptedAppSecret: string; updatedAt: string } | null> {
    return this.db.prepare(`
      INSERT INTO wechat_credential (id, app_id, encrypted_app_secret)
      VALUES (1, ?, ?)
      ON CONFLICT(id) DO UPDATE SET app_id = excluded.app_id,
        encrypted_app_secret = excluded.encrypted_app_secret,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      RETURNING app_id AS appId, encrypted_app_secret AS encryptedAppSecret,
                updated_at AS updatedAt
    `).bind(appId, encryptedAppSecret)
      .first<{ appId: string; encryptedAppSecret: string; updatedAt: string }>();
  }

  async deleteWechatCredential(): Promise<void> {
    await this.db.prepare("DELETE FROM wechat_credential WHERE id = 1").run();
  }
}
