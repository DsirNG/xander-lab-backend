import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import test from "node:test";
import coreWorker from "../.test-build/index.js";
import { IdentitySessionService } from "../.test-build/services/identity-session-service.js";
import {
  signJwtToken,
  verifyAccessToken,
  verifyRefreshToken,
} from "../.test-build/auth.js";

const secret = "local-test-jwt-secret-at-least-32-bytes";

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function signToken(claims, signingSecret = secret, algorithm = "HS256") {
  const digest = { HS256: "sha256", HS384: "sha384", HS512: "sha512" }[algorithm];
  const header = encode({ alg: algorithm, typ: "JWT" });
  const payload = encode(claims);
  const message = header + "." + payload;
  const signature = createHmac(digest, signingSecret).update(message).digest("base64url");
  return message + "." + signature;
}

function createDb(user) {
  const revokedHashes = new Set();
  const state = { cleanupCalls: 0, refreshCleanupCalls: 0 };
  return {
    revokedHashes,
    state,
    prepare(query) {
      let bindings = [];
      return {
        query,
        bind(...values) {
          bindings = values;
          return this;
        },
        async first() {
          if (query.includes("FROM sys_user WHERE id = ?")) {
            if (revokedHashes.has(bindings[1])) return null;
            return user.id === bindings[0] ? { ...user } : null;
          }
          if (query.includes("FROM auth_token_revocation")) {
            return revokedHashes.has(bindings[0]) ? { revoked: 1 } : null;
          }
          if (query.includes("WHERE username = ? COLLATE NOCASE")) return null;
          throw new Error("Unexpected Core D1 read");
        },
        async all() {
          return { results: [] };
        },
        async run() {
          if (query.includes("INSERT INTO auth_token_revocation")) {
            revokedHashes.add(bindings[0]);
            return { success: true, meta: { changes: 1 } };
          }
          if (query.startsWith("DELETE FROM auth_token_revocation")) {
            state.cleanupCalls += 1;
            return { success: true, meta: { changes: 1 } };
          }
          if (query.includes("auth_refresh_token_history") || query.includes("auth_refresh_session")) {
            state.refreshCleanupCalls += 1;
            return { success: true, meta: { changes: 0 } };
          }
          if (!query.startsWith("UPDATE sys_user SET username")) {
            throw new Error("Unexpected Core D1 write");
          }
          [user.username, user.nickname, user.avatar] = bindings;
          return { success: true, meta: { changes: 1 } };
        },
      };
    },
    async batch(statements) {
      const results = [];
      for (const statement of statements) results.push(await statement.run());
      return results;
    },
  };
}

const user = {
  id: 27,
  username: "reader@example.com",
  nickname: "Reader",
  email: "reader@example.com",
  avatar: null,
  role: "USER",
  tier: "FREE",
  tierExpiresAt: null,
  status: 1,
  createdAt: "2026-10-10 10:00:00",
};

test("Java-compatible HS256 access token verifies; malformed, expired and refresh tokens fail", async () => {
  const valid = signToken({ sub: "27", exp: 2_000_000_000, type: "access" });
  assert.equal((await verifyAccessToken(valid, secret, 1_900_000_000))?.sub, "27");
  assert.equal(await verifyAccessToken(valid, "too-short", 1_900_000_000), null);
  assert.equal(
    await verifyAccessToken(
      signToken({ sub: "27", exp: 1_800_000_000, type: "access" }),
      secret,
      1_900_000_000,
    ),
    null,
  );
  assert.equal(
    await verifyAccessToken(
      signToken({ sub: "27", exp: 2_000_000_000, type: "refresh" }),
      secret,
      1_900_000_000,
    ),
    null,
  );
  const tamperedParts = valid.split(".");
  tamperedParts[2] = (tamperedParts[2][0] === "A" ? "B" : "A") + tamperedParts[2].slice(1);
  assert.equal(await verifyAccessToken(tamperedParts.join("."), secret, 1_900_000_000), null);
});

test("Java key lengths that select HS384 and HS512 remain interoperable", async () => {
  for (const [algorithm, signingSecret] of [
    ["HS384", "m".repeat(48)],
    ["HS512", "l".repeat(64)],
  ]) {
    const token = signToken(
      { sub: "27", exp: 2_000_000_000, type: "access" },
      signingSecret,
      algorithm,
    );
    assert.equal(
      (await verifyAccessToken(token, signingSecret, 1_900_000_000))?.sub,
      "27",
    );
  }
});

test("Worker-issued access and refresh JWTs use the Java-compatible HMAC algorithm", async () => {
  for (const [length, algorithm] of [[32, "HS256"], [48, "HS384"], [64, "HS512"]]) {
    const signingSecret = "k".repeat(length);
    const accessToken = await signJwtToken(
      "27",
      signingSecret,
      { role: "USER", type: "access" },
      600,
      1_900_000_000,
    );
    const encodedHeader = accessToken.split(".")[0].replace(/-/g, "+").replace(/_/g, "/");
    const header = JSON.parse(Buffer.from(encodedHeader, "base64").toString("utf8"));
    assert.equal(header.alg, algorithm);
    assert.equal((await verifyAccessToken(accessToken, signingSecret, 1_900_000_001))?.sub, "27");

    const refreshToken = await signJwtToken(
      "27",
      signingSecret,
      { type: "refresh" },
      1200,
      1_900_000_000,
    );
    assert.equal((await verifyRefreshToken(refreshToken, signingSecret, 1_900_000_001))?.sub, "27");
    assert.equal(await verifyAccessToken(refreshToken, signingSecret, 1_900_000_001), null);
  }
});

test("Core /api/auth/me and /api/auth/profile read and update only the token owner", async () => {
  const mutableUser = { ...user };
  const token = signToken({ sub: String(mutableUser.id), exp: 2_000_000_000, type: "access" });
  const env = {
    INTERNAL_SERVICE_TOKEN: "internal-test-token",
    JWT_SECRET: secret,
    DB: createDb(mutableUser),
  };
  const headers = {
    authorization: "Bearer " + token,
    "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN,
  };

  const profile = await coreWorker.fetch(
    new Request("https://core.test/api/auth/me", { headers }),
    env,
  );
  assert.equal(profile.status, 200);
  assert.deepEqual((await profile.json()).data, {
    username: "reader@example.com",
    nickname: "Reader",
    avatar: null,
    role: "USER",
    tier: "FREE",
    tierExpiresAt: null,
    email: "reader@example.com",
    createdAt: "2026-10-10T10:00:00",
  });

  const updated = await coreWorker.fetch(
    new Request("https://core.test/api/auth/profile", {
      method: "PUT",
      headers: { ...headers, "content-type": "application/json" },
      body: JSON.stringify({ username: "reader_2", nickname: "New Name", avatar: "https://img.test/a.png" }),
    }),
    env,
  );
  assert.equal(updated.status, 200);
  assert.equal((await updated.json()).data.username, "reader_2");
  assert.equal(mutableUser.nickname, "New Name");
  assert.equal(mutableUser.avatar, "https://img.test/a.png");
});

test("Core private profile endpoints reject missing or invalid bearer tokens", async () => {
  const env = {
    INTERNAL_SERVICE_TOKEN: "internal-test-token",
    DB: createDb({ ...user }),
  };
  const response = await coreWorker.fetch(
    new Request("https://core.test/api/auth/me", {
      headers: { "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN },
    }),
    env,
  );
  assert.equal(response.status, 401);
  assert.equal((await response.json()).message, "未登录或登录已过期");
});

test("Core /api/auth/validate returns false for absent, invalid, refresh, or disabled tokens", async () => {
  const accessToken = signToken({ sub: String(user.id), exp: 2_000_000_000, type: "access" });
  const refreshToken = signToken({ sub: String(user.id), exp: 2_000_000_000, type: "refresh" });
  const env = {
    INTERNAL_SERVICE_TOKEN: "internal-test-token",
    JWT_SECRET: secret,
    DB: createDb({ ...user, status: 1 }),
  };
  const serviceHeader = { "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN };
  const requestFor = (token) => new Request("https://core.test/api/auth/validate", {
    headers: {
      ...serviceHeader,
      ...(token ? { authorization: "Bearer " + token } : {}),
    },
  });

  const valid = await coreWorker.fetch(requestFor(accessToken), env);
  assert.equal((await valid.json()).data, true);
  for (const token of [null, "not-a-token", refreshToken]) {
    const invalid = await coreWorker.fetch(requestFor(token), env);
    assert.equal(invalid.status, 200);
    assert.equal((await invalid.json()).data, false);
  }
  const disabled = await coreWorker.fetch(requestFor(accessToken), {
    ...env,
    DB: createDb({ ...user, status: 0 }),
  });
  assert.equal((await disabled.json()).data, false);
});

test("Core logout revokes only the presented access token until its expiry", async () => {
  const loggedOutToken = signToken({ sub: String(user.id), exp: 2_000_000_000, type: "access" });
  const otherDeviceToken = signToken({
    sub: String(user.id),
    exp: 2_000_000_000,
    type: "access",
    jti: "another-device",
  });
  const db = createDb({ ...user, status: 1 });
  const env = {
    INTERNAL_SERVICE_TOKEN: "internal-test-token",
    JWT_SECRET: secret,
    DB: db,
  };
  const serviceHeader = { "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN };

  const logout = await coreWorker.fetch(
    new Request("https://core.test/api/auth/logout", {
      method: "POST",
      headers: { ...serviceHeader, authorization: "Bearer " + loggedOutToken },
    }),
    env,
  );
  assert.equal(logout.status, 200);
  assert.equal((await logout.json()).data, null);

  const loggedOut = await coreWorker.fetch(
    new Request("https://core.test/api/auth/me", {
      headers: { ...serviceHeader, authorization: "Bearer " + loggedOutToken },
    }),
    env,
  );
  assert.equal(loggedOut.status, 401);

  const otherDevice = await coreWorker.fetch(
    new Request("https://core.test/api/auth/me", {
      headers: { ...serviceHeader, authorization: "Bearer " + otherDeviceToken },
    }),
    env,
  );
  assert.equal(otherDevice.status, 200);
});

test("Core refresh rotates Java-compatible legacy JWTs and preserves the user response contract", async () => {
  const refreshToken = signToken({
    sub: String(user.id),
    exp: 2_000_000_000,
    type: "refresh",
  });
  const env = {
    INTERNAL_SERVICE_TOKEN: "internal-test-token",
    JWT_SECRET: secret,
    JWT_ACCESS_TTL_SECONDS: "600",
    JWT_REFRESH_TTL_SECONDS: "1200",
    DB: createDb({ ...user, status: 1 }),
  };
  const response = await coreWorker.fetch(
    new Request("https://core.test/api/auth/refresh", {
      method: "POST",
      headers: {
        "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN,
        "content-type": "application/json",
      },
      body: JSON.stringify({ refreshToken }),
    }),
    env,
  );

  assert.equal(response.status, 200);
  const result = await response.json();
  assert.equal(result.code, 200);
  assert.equal(result.data.tokenType, "Bearer");
  assert.equal(result.data.expiresIn, 600);
  assert.equal(result.data.userInfo.username, user.username);
  assert.equal((await verifyAccessToken(result.data.accessToken, secret))?.sub, String(user.id));
  assert.equal((await verifyRefreshToken(result.data.refreshToken, secret))?.sub, String(user.id));

  const refreshedAgain = await coreWorker.fetch(
    new Request("https://core.test/api/auth/refresh", {
      method: "POST",
      headers: {
        "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN,
        "content-type": "application/json",
      },
      body: JSON.stringify({ refreshToken: result.data.refreshToken }),
    }),
    env,
  );
  assert.equal(refreshedAgain.status, 200);
});

test("Core stateful refresh requires its encryption key and never downgrades to JWT refresh", async () => {
  const env = {
    INTERNAL_SERVICE_TOKEN: "internal-test-token",
    JWT_SECRET: secret,
    AUTH_REFRESH_PROTOCOL_ENABLED: "true",
    DB: createDb({ ...user, status: 1 }),
  };
  const response = await coreWorker.fetch(
    new Request("https://core.test/api/auth/refresh", {
      method: "POST",
      headers: {
        "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN,
        "content-type": "application/json",
      },
      body: JSON.stringify({ refreshToken: "untrusted" }),
    }),
    env,
  );
  assert.equal(response.status, 503);
});

test("Core scheduled cleanup removes expired token revocations", async () => {
  const env = { DB: createDb({ ...user }) };
  await coreWorker.scheduled({}, env);
  assert.equal(env.DB.state.cleanupCalls, 1);
  assert.equal(env.DB.state.refreshCleanupCalls, 3);
});

function createRefreshRepository() {
  const sessions = new Map();
  const history = new Map();
  const reuseEvents = [];
  const revokedAccess = new Set();
  return {
    sessions,
    history,
    reuseEvents,
    revokedAccess,
    async findActiveUserById(userId) {
      return userId === user.id ? { ...user, status: 1, authVersion: 0 } : null;
    },
    async recordTokenRevocation(fingerprint) { revokedAccess.add(fingerprint); },
    async deleteExpiredTokenRevocations() {},
    async createRefreshSession(session) { sessions.set(session.sid, { ...session }); },
    async findRefreshSession(sid) { return sessions.get(sid) ? { ...sessions.get(sid) } : null; },
    async findRefreshTokenHistory(tokenId) { return history.get(tokenId) ? { ...history.get(tokenId) } : null; },
    async rotateRefreshSession(write) {
      const current = sessions.get(write.session.sid);
      if (!current || current.status !== "ACTIVE" || current.currentTokenId !== write.previousTokenId
        || current.currentTokenHash !== write.previousTokenHash || current.generation !== write.previousGeneration
        || Date.parse(current.expiresAt) <= Date.parse(write.usedAt)) return false;
      sessions.set(write.session.sid, { ...write.session });
      history.set(write.previousTokenId, {
        tokenId: write.previousTokenId,
        sid: write.session.sid,
        tokenHash: write.previousTokenHash,
        generation: write.previousGeneration,
        usedAt: write.usedAt,
        successorTokenId: write.successorTokenId,
        graceExpiresAt: write.graceExpiresAt,
        retainedUntil: write.retainedUntil,
        reuseDetectedAt: null,
        successorTokenCiphertext: write.successorTokenCiphertext,
        successorCiphertextExpiresAt: write.graceExpiresAt,
      });
      return true;
    },
    async revokeRefreshFamily(sid, tokenId, occurredAt) {
      const target = sessions.get(sid);
      for (const entry of sessions.values()) {
        if (target && entry.familyId === target.familyId && entry.status === "ACTIVE") {
          entry.status = "REVOKED";
          entry.revokedAt = occurredAt;
          entry.revokedReason = "REUSE_DETECTED";
        }
      }
      const previous = history.get(tokenId);
      if (previous) previous.reuseDetectedAt = occurredAt;
      reuseEvents.push({ sid, tokenId, occurredAt });
    },
    async revokeRefreshSession(sid, reason, revokedAt) {
      const entry = sessions.get(sid);
      if (entry?.status === "ACTIVE") Object.assign(entry, { status: "REVOKED", revokedReason: reason, revokedAt });
    },
    async revokeRefreshSessionsForAccount(accountId, reason, revokedAt) {
      for (const entry of sessions.values()) {
        if (entry.accountId === accountId && entry.status === "ACTIVE") {
          Object.assign(entry, { status: "REVOKED", revokedReason: reason, revokedAt });
        }
      }
    },
    async cleanupRefreshData() {},
  };
}

test("stateful refresh rotates, replays the same successor during grace, and revokes on later reuse", async () => {
  let now = Date.parse("2026-10-10T00:00:00.000Z");
  const repository = createRefreshRepository();
  const service = new IdentitySessionService(repository, () => now);
  const settings = {
    jwtSecret: secret,
    refreshProtocolEnabled: "true",
    refreshEncryptionKey: Buffer.alloc(32, 17).toString("base64"),
    accessTtlSeconds: "1800",
    refreshGraceSeconds: "25",
    refreshRetentionHours: "24",
  };
  const issued = await service.issueRefreshSession(user.id, settings);
  assert.ok(issued?.refreshToken.startsWith("rt_"));

  const rotated = await service.refresh(issued.refreshToken, settings);
  assert.equal(rotated.kind, "refreshed");
  assert.notEqual(rotated.refreshToken, issued.refreshToken);
  assert.equal((await verifyAccessToken(rotated.accessToken, secret, Math.floor(now / 1000)))?.sid, issued.sid);
  const replayed = await service.refresh(issued.refreshToken, settings);
  assert.equal(replayed.kind, "refreshed");
  assert.equal(replayed.refreshToken, rotated.refreshToken);
  assert.equal(repository.reuseEvents.length, 0);

  now += 25_001;
  assert.deepEqual(await service.refresh(issued.refreshToken, settings), { kind: "invalid" });
  assert.equal(repository.reuseEvents.length, 1);
  assert.equal(repository.sessions.get(issued.sid).status, "REVOKED");
  assert.deepEqual(await service.refresh(rotated.refreshToken, settings), { kind: "invalid" });
});

test("stateful refresh rejects forged secrets without revoking a session and logout revokes its session", async () => {
  let now = Date.parse("2026-10-10T00:00:00.000Z");
  const repository = createRefreshRepository();
  const service = new IdentitySessionService(repository, () => now);
  const settings = {
    jwtSecret: secret,
    refreshProtocolEnabled: "true",
    refreshEncryptionKey: Buffer.alloc(32, 29).toString("base64"),
  };
  const issued = await service.issueRefreshSession(user.id, settings);
  const current = repository.sessions.get(issued.sid);
  const forged = `rt_${issued.sid}.${current.currentTokenId}.${"A".repeat(43)}`;
  assert.deepEqual(await service.refresh(forged, settings), { kind: "invalid" });
  assert.equal(current.status, "ACTIVE");
  assert.equal(repository.reuseEvents.length, 0);

  const refreshed = await service.refresh(issued.refreshToken, settings);
  assert.equal(refreshed.kind, "refreshed");
  await service.logout(refreshed.accessToken, secret);
  assert.equal(repository.sessions.get(issued.sid).status, "REVOKED");
  assert.deepEqual(await service.refresh(refreshed.refreshToken, settings), { kind: "invalid" });
});

test("stateful refresh expires on the absolute session deadline and rejects missing encryption configuration", async () => {
  let now = Date.parse("2026-10-10T00:00:00.000Z");
  const repository = createRefreshRepository();
  const service = new IdentitySessionService(repository, () => now);
  const settings = {
    jwtSecret: secret,
    refreshProtocolEnabled: "true",
    refreshEncryptionKey: Buffer.alloc(32, 41).toString("base64"),
    refreshSessionTtlSeconds: "1",
  };
  const issued = await service.issueRefreshSession(user.id, settings);
  now += 1001;
  assert.deepEqual(await service.refresh(issued.refreshToken, settings), { kind: "invalid" });
  assert.equal(repository.reuseEvents.length, 0);
  assert.equal((await service.refresh(issued.refreshToken, { ...settings, refreshEncryptionKey: undefined })).kind,
    "unconfigured");
});

test("Core resolves Agent access tokens only for active users", async () => {
  const token = signToken({ sub: String(user.id), exp: 2_000_000_000, type: "access" });
  const env = {
    INTERNAL_SERVICE_TOKEN: "internal-test-token",
    INTERNAL_SERVICE_TOKEN_NEXT: "next-internal-test-token",
    JWT_SECRET: secret,
    DB: createDb({ ...user, status: 1 }),
  };
  const headers = {
    "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN,
    "content-type": "application/json",
  };
  const active = await coreWorker.fetch(
    new Request("https://core.test/internal/agent/auth/resolve", {
      method: "POST",
      headers,
      body: JSON.stringify({ accessToken: token }),
    }),
    env,
  );
  assert.equal(active.status, 200);
  assert.deepEqual(await active.json(), { userId: user.id });

  const nextToken = await coreWorker.fetch(
    new Request("https://core.test/internal/agent/auth/resolve", {
      method: "POST",
      headers: {
        "x-dinqor-service-token": env.INTERNAL_SERVICE_TOKEN_NEXT,
        "content-type": "application/json",
      },
      body: JSON.stringify({ accessToken: token }),
    }),
    env,
  );
  assert.equal(nextToken.status, 200);
  assert.deepEqual(await nextToken.json(), { userId: user.id });

  const disabled = await coreWorker.fetch(
    new Request("https://core.test/internal/agent/auth/resolve", {
      method: "POST",
      headers,
      body: JSON.stringify({ accessToken: token }),
    }),
    { ...env, DB: createDb({ ...user, status: 0 }) },
  );
  assert.equal(disabled.status, 200);
  assert.deepEqual(await disabled.json(), { userId: null });
});
