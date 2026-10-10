import { signJwtToken, verifyAccessToken, verifyRefreshToken } from "../auth.js";
import type {
  IdentitySessionRepository,
  RefreshSessionRecord,
} from "../interfaces/identity-session-repository.js";
import type { IdentityUser } from "../interfaces/identity-repository.js";
import { fingerprintAccessToken } from "./identity-auth-service.js";

export interface IdentitySessionSettings {
  jwtSecret?: string;
  refreshProtocolEnabled?: string;
  refreshEncryptionKey?: string;
  accessTtlSeconds?: string;
  refreshTtlSeconds?: string;
  refreshSessionTtlSeconds?: string;
  refreshGraceSeconds?: string;
  refreshRetentionHours?: string;
}

export type RefreshSessionResult =
  | { kind: "unconfigured" }
  | { kind: "invalid" }
  | {
    kind: "refreshed";
    user: IdentityUser;
    accessToken: string;
    refreshToken: string;
    expiresIn: number;
  };

export interface IssuedRefreshSession {
  sid: string;
  refreshToken: string;
}

const DEFAULT_JWT_TTL_SECONDS = 604800;
const DEFAULT_STATEFUL_ACCESS_TTL_SECONDS = 1800;
const DEFAULT_SESSION_TTL_SECONDS = 2_592_000;
const DEFAULT_GRACE_SECONDS = 25;
const DEFAULT_RETENTION_HOURS = 24;
const MAX_HISTORY_GENERATIONS = 48;

interface ParsedRefreshToken {
  sid: string;
  tokenId: string;
  secret: string;
}

function configuredPositiveInteger(value: string | undefined, fallback: number): number {
  if (!value || !/^\d+$/.test(value)) return fallback;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function arrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

function decodeBase64(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  try {
    return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
  } catch {
    return null;
  }
}

function newId(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(16)));
}

function newSecret(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)));
}

function parseRefreshToken(raw: string): ParsedRefreshToken | null {
  const body = raw.startsWith("rt_") ? raw.slice(3) : raw;
  const parts = body.split(".");
  if (parts.length !== 3 || parts.some((part) => !part || part.length > 64)) return null;
  return { sid: parts[0], tokenId: parts[1], secret: parts[2] };
}

async function tokenHash(tokenId: string, secret: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${tokenId}:${secret}`));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function constantTimeEquals(left: string, right: string): boolean {
  const width = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < width; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^ (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

async function encryptionKey(value: string | undefined): Promise<CryptoKey | null> {
  if (!value) return null;
  const bytes = decodeBase64(value);
  if (!bytes || bytes.byteLength !== 32) return null;
  return crypto.subtle.importKey("raw", arrayBuffer(bytes), { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

async function encryptSuccessor(value: string, key: CryptoKey): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: arrayBuffer(iv) }, key, arrayBuffer(new TextEncoder().encode(value)),
  );
  return `${base64(iv)}.${base64(new Uint8Array(encrypted))}`;
}

async function decryptSuccessor(value: string | null, key: CryptoKey): Promise<string | null> {
  if (!value) return null;
  const parts = value.split(".");
  if (parts.length !== 2) return null;
  const iv = decodeBase64(parts[0]);
  const encrypted = decodeBase64(parts[1]);
  if (!iv || iv.byteLength !== 12 || !encrypted) return null;
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: arrayBuffer(iv) }, key, arrayBuffer(encrypted),
    );
    return new TextDecoder("utf-8", { fatal: true }).decode(plain);
  } catch {
    return null;
  }
}

export class IdentitySessionService {
  constructor(
    private readonly sessions: IdentitySessionRepository,
    private readonly now: () => number = Date.now,
  ) {}

  async issueRefreshSession(
    userId: number,
    settings: IdentitySessionSettings,
  ): Promise<IssuedRefreshSession | null> {
    if (settings.refreshProtocolEnabled !== "true") return null;
    if (!settings.jwtSecret || new TextEncoder().encode(settings.jwtSecret).byteLength < 32
      || !await encryptionKey(settings.refreshEncryptionKey)) {
      throw new Error("Stateful refresh is missing its signing or encryption secret");
    }
    const sid = newId();
    const familyId = newId();
    const tokenId = newId();
    const secret = newSecret();
    const issuedAt = new Date(this.now());
    const record: RefreshSessionRecord = {
      sid,
      accountId: userId,
      familyId,
      currentTokenId: tokenId,
      currentTokenHash: await tokenHash(tokenId, secret),
      generation: 0,
      status: "ACTIVE",
      issuedAt: issuedAt.toISOString(),
      lastRotatedAt: null,
      expiresAt: new Date(issuedAt.getTime()
        + configuredPositiveInteger(settings.refreshSessionTtlSeconds, DEFAULT_SESSION_TTL_SECONDS) * 1000)
        .toISOString(),
      revokedAt: null,
      revokedReason: null,
    };
    await this.sessions.createRefreshSession(record);
    return { sid, refreshToken: `rt_${sid}.${tokenId}.${secret}` };
  }

  async refresh(token: string, settings: IdentitySessionSettings): Promise<RefreshSessionResult> {
    if (!settings.jwtSecret || new TextEncoder().encode(settings.jwtSecret).byteLength < 32) {
      return { kind: "unconfigured" };
    }
    if (settings.refreshProtocolEnabled === "true") return this.refreshStateful(token, settings);
    return this.refreshStateless(token, settings);
  }

  private async refreshStateless(token: string, settings: IdentitySessionSettings): Promise<RefreshSessionResult> {
    const claims = await verifyRefreshToken(token, settings.jwtSecret!, Math.floor(this.now() / 1000));
    if (!claims) return { kind: "invalid" };
    const user = await this.sessions.findActiveUserById(Number(claims.sub));
    const tokenVersion = claims.ver === undefined ? 0 : claims.ver;
    if (!user || !Number.isSafeInteger(tokenVersion) || tokenVersion !== (user.authVersion ?? 0)) {
      return { kind: "invalid" };
    }
    const accessTtlSeconds = configuredPositiveInteger(settings.accessTtlSeconds, DEFAULT_JWT_TTL_SECONDS);
    const refreshTtlSeconds = configuredPositiveInteger(settings.refreshTtlSeconds, DEFAULT_JWT_TTL_SECONDS);
    const accessToken = await signJwtToken(
      String(user.id), settings.jwtSecret!,
      { role: user.role, ver: user.authVersion ?? 0, type: "access" },
      accessTtlSeconds, Math.floor(this.now() / 1000),
    );
    const refreshToken = await signJwtToken(
      String(user.id), settings.jwtSecret!,
      { ver: user.authVersion ?? 0, type: "refresh" },
      refreshTtlSeconds, Math.floor(this.now() / 1000),
    );
    return { kind: "refreshed", user, accessToken, refreshToken, expiresIn: accessTtlSeconds };
  }

  private async refreshStateful(token: string, settings: IdentitySessionSettings): Promise<RefreshSessionResult> {
    const key = await encryptionKey(settings.refreshEncryptionKey);
    const parsed = parseRefreshToken(token);
    if (!key || !parsed) return key ? { kind: "invalid" } : { kind: "unconfigured" };
    const session = await this.sessions.findRefreshSession(parsed.sid);
    if (!session) return { kind: "invalid" };
    const user = await this.sessions.findActiveUserById(session.accountId);
    if (!user || session.status !== "ACTIVE" || Date.parse(session.expiresAt) <= this.now()) {
      return { kind: "invalid" };
    }

    const presentedHash = await tokenHash(parsed.tokenId, parsed.secret);
    if (parsed.tokenId === session.currentTokenId) {
      if (!constantTimeEquals(session.currentTokenHash, presentedHash)) return { kind: "invalid" };
      const rotated = await this.rotate(session, parsed, presentedHash, settings, key);
      if (rotated) return this.successfulRefresh(user, session.sid, rotated, settings);
    }

    const history = await this.sessions.findRefreshTokenHistory(parsed.tokenId);
    if (!history || history.sid !== session.sid || !constantTimeEquals(history.tokenHash, presentedHash)) {
      return { kind: "invalid" };
    }
    const now = this.now();
    if (now <= Date.parse(history.graceExpiresAt)) {
      if (!history.successorCiphertextExpiresAt
        || now > Date.parse(history.successorCiphertextExpiresAt)) return { kind: "invalid" };
      const successor = await decryptSuccessor(history.successorTokenCiphertext, key);
      return successor ? this.successfulRefresh(user, session.sid, successor, settings) : { kind: "invalid" };
    }
    if (now <= Date.parse(history.retainedUntil)) {
      await this.sessions.revokeRefreshFamily(session.sid, parsed.tokenId, new Date(now).toISOString());
    }
    return { kind: "invalid" };
  }

  private async rotate(
    current: RefreshSessionRecord,
    presented: ParsedRefreshToken,
    previousHash: string,
    settings: IdentitySessionSettings,
    key: CryptoKey,
  ): Promise<string | null> {
    const now = this.now();
    const nowText = new Date(now).toISOString();
    const tokenId = newId();
    const secret = newSecret();
    const refreshToken = `rt_${current.sid}.${tokenId}.${secret}`;
    const graceExpiresAt = new Date(now
      + configuredPositiveInteger(settings.refreshGraceSeconds, DEFAULT_GRACE_SECONDS) * 1000).toISOString();
    const retainedUntil = new Date(now
      + configuredPositiveInteger(settings.refreshRetentionHours, DEFAULT_RETENTION_HOURS) * 3_600_000)
      .toISOString();
    const next: RefreshSessionRecord = {
      ...current,
      currentTokenId: tokenId,
      currentTokenHash: await tokenHash(tokenId, secret),
      generation: current.generation + 1,
      lastRotatedAt: nowText,
    };
    const committed = await this.sessions.rotateRefreshSession({
      session: next,
      previousTokenId: presented.tokenId,
      previousTokenHash: previousHash,
      previousGeneration: current.generation,
      usedAt: nowText,
      graceExpiresAt,
      retainedUntil,
      successorTokenId: tokenId,
      successorTokenCiphertext: await encryptSuccessor(refreshToken, key),
    });
    return committed ? refreshToken : null;
  }

  private async successfulRefresh(
    user: IdentityUser,
    sid: string,
    refreshToken: string,
    settings: IdentitySessionSettings,
  ): Promise<RefreshSessionResult> {
    const accessTtlSeconds = configuredPositiveInteger(
      settings.accessTtlSeconds, DEFAULT_STATEFUL_ACCESS_TTL_SECONDS,
    );
    const accessToken = await signJwtToken(
      String(user.id), settings.jwtSecret!,
      { role: user.role, ver: user.authVersion ?? 0, type: "access", sid },
      accessTtlSeconds, Math.floor(this.now() / 1000),
    );
    return { kind: "refreshed", user, accessToken, refreshToken, expiresIn: accessTtlSeconds };
  }

  async logout(accessToken: string, jwtSecret: string | undefined): Promise<void> {
    if (!jwtSecret) return;
    const claims = await verifyAccessToken(accessToken, jwtSecret, Math.floor(this.now() / 1000));
    if (!claims) return;
    if (typeof claims.sid === "string" && claims.sid) {
      await this.sessions.revokeRefreshSession(claims.sid, "LOGOUT", new Date(this.now()).toISOString());
    }
    await this.sessions.recordTokenRevocation(
      await fingerprintAccessToken(accessToken),
      claims.exp * 1000,
      this.now(),
    );
  }

  cleanupExpiredRevocations(now: number): Promise<void> {
    return this.sessions.deleteExpiredTokenRevocations(now);
  }

  cleanupRefreshData(now: number, batchSize = 500): Promise<void> {
    return this.sessions.cleanupRefreshData(new Date(now).toISOString(), batchSize);
  }

  findActiveUserById(userId: number): Promise<IdentityUser | null> {
    return this.sessions.findActiveUserById(userId);
  }
}
