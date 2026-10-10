import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import coreWorker from "../.test-build/index.js";
import { signJwtToken } from "../.test-build/auth.js";
import { verifyPassword } from "../.test-build/password.js";

const jwtSecret = "local-test-jwt-secret-at-least-32-bytes";
const serviceToken = "core-flow-test-token";

function createDb() {
  const users = [];
  const revoked = new Set();
  const pointAccounts = new Map();
  const ledger = [];
  let nextId = 1;
  return {
    users,
    revoked,
    pointAccounts,
    ledger,
    seedUser(input) {
      const user = {
        id: nextId++, username: input.username, password: input.password,
        nickname: input.nickname ?? null, email: input.email ?? null, avatar: null,
        openid: input.openid ?? null, registrationGrantPending: 0,
        role: input.role ?? "USER", tier: "FREE", tierExpiresAt: null, status: 1,
        createdAt: "2026-10-10T00:00:00.000Z", authVersion: 0,
      };
      users.push(user);
      return { ...user };
    },
    prepare(query) {
      let values = [];
      const sql = query.replace(/\s+/g, " ").trim();
      const statement = {
        bind(...bound) {
          values = bound;
          return statement;
        },
        sql,
        async applyBatch() {
          if (sql.includes("SET password = ?, auth_version = auth_version + 1")) {
            const user = users.find((item) => item.id === values[1] && item.status === 1);
            if (user) {
              user.password = values[0];
              user.authVersion += 1;
            }
            return;
          }
          if (sql.includes("SET status = ?, auth_version = auth_version + 1")) {
            const user = users.find((item) => item.id === values[1]);
            if (user && user.status !== values[0]) {
              user.status = values[0];
              user.authVersion += 1;
            }
            return;
          }
          if (sql.includes("UPDATE auth_refresh_session SET status = 'REVOKED'")) return;
          if (sql.includes("INSERT INTO sys_user_points")) {
            const user = users.find((item) => item.id === values[0] && item.registrationGrantPending === 1);
            if (user && !pointAccounts.has(user.id)) pointAccounts.set(user.id, 0);
            return;
          }
          if (sql.includes("INSERT INTO points_ledger")) {
            const [amount, grant, _price, userId] = values;
            const user = users.find((item) => item.id === userId && item.registrationGrantPending === 1);
            const key = `grant:user:${userId}`;
            if (!user || user.status !== 1 || ledger.some((item) => item.bizType === "grant" && item.bizKey === key)) return;
            const balance = (pointAccounts.get(userId) ?? 0) + amount;
            pointAccounts.set(userId, balance);
            ledger.push({ userId, bizType: "grant", bizKey: key, amount, balanceAfter: balance });
            return;
          }
          if (sql.includes("UPDATE sys_user SET registration_grant_pending = 0")) {
            const user = users.find((item) => item.id === values[0]);
            if (user) user.registrationGrantPending = 0;
          }
        },
        async first() {
          if (sql.includes("INSERT INTO sys_user")) {
            const [username, password, nickname, email, avatar, openid] = values;
            if (users.some((user) => (user.email && email && user.email.toLowerCase() === email.toLowerCase())
              || (user.openid && openid && user.openid === openid)
              || user.username.toLowerCase() === username.toLowerCase())) return null;
            const user = {
              id: nextId++, username, password, nickname, email, avatar, openid: openid ?? null,
              registrationGrantPending: sql.includes("registration_grant_pending") ? 1 : 0,
              role: "USER",
              tier: "FREE", tierExpiresAt: null, status: 1,
              createdAt: "2026-10-10T00:00:00.000Z", authVersion: 0,
            };
            users.push(user);
            return { ...user };
          }
          if (sql.includes("FROM sys_user WHERE email = ? COLLATE NOCASE")) {
            return users.find((user) => (user.email && user.email.toLowerCase() === String(values[0]).toLowerCase())
              || user.username.toLowerCase() === String(values[1]).toLowerCase()) ?? null;
          }
          if (sql.includes("FROM sys_user WHERE openid = ?")) {
            const user = users.find((item) => item.openid === values[0]);
            return user ? { ...user } : null;
          }
          if (sql.includes("FROM sys_user WHERE username = ? COLLATE NOCASE")) {
            const user = users.find((item) => item.username.toLowerCase() === String(values[0]).toLowerCase()
              || (item.email && item.email.toLowerCase() === String(values[1]).toLowerCase()));
            return user ? { ...user } : null;
          }
          if (sql.includes("FROM sys_user WHERE id = ?")) {
            const user = users.find((item) => item.id === values[0]);
            if (!user || revoked.has(values[1])) return null;
            return { ...user };
          }
          if (sql.includes("FROM auth_token_revocation")) {
            return revoked.has(values[0]) ? { revoked: 1 } : null;
          }
          if (sql.includes("SELECT COUNT(*) AS total FROM sys_user")) {
            return { total: users.length };
          }
          if (sql.includes("WHERE username = ? COLLATE NOCASE")) return null;
          if (sql.includes("SELECT password FROM sys_user")) {
            const user = users.find((item) => item.id === values[0]);
            return user ? { password: user.password } : null;
          }
          throw new Error(`Unexpected auth flow read: ${sql}`);
        },
        async run() {
          if (sql.includes("SET password = ?, auth_version = auth_version + 1")) {
            const user = users.find((item) => item.id === values[1]);
            if (!user) return { success: true, meta: { changes: 0 } };
            user.password = values[0];
            user.authVersion += 1;
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.includes("INSERT INTO auth_token_revocation")) {
            revoked.add(values[0]);
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.includes("SET status = ?, auth_version = auth_version + 1")) {
            const user = users.find((item) => item.id === values[1]);
            if (!user || user.status === values[0]) return { success: true, meta: { changes: 0 } };
            user.status = values[0];
            user.authVersion += 1;
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.includes("UPDATE sys_user SET openid = ?")) {
            const user = users.find((item) => item.id === values[1] && item.openid === null);
            if (!user || users.some((item) => item.openid === values[0])) return { success: true, meta: { changes: 0 } };
            user.openid = values[0];
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.includes("UPDATE sys_user SET email = ?")) {
            const user = users.find((item) => item.id === values[1] && item.status === 1);
            if (!user || (user.email && user.email !== values[2])) return { success: true, meta: { changes: 0 } };
            user.email = values[0];
            return { success: true, meta: { changes: 1 } };
          }
          throw new Error(`Unexpected auth flow write: ${sql}`);
        },
        async all() {
          if (sql.includes("FROM sys_user")) {
            const offset = values.length >= 2 ? values[values.length - 1] : 0;
            const limit = values.length >= 2 ? values[values.length - 2] : users.length;
            return {
              results: users.slice(offset, offset + limit).map((user) => ({
                id: user.id, username: user.username, nickname: user.nickname,
                email: user.email, role: user.role, status: user.status, createdAt: user.createdAt,
              })),
            };
          }
          return { results: [] };
        },
      };
      return statement;
    },
    async batch(statements) {
      for (const statement of statements) await statement.applyBatch();
      return [];
    },
  };
}

function installServiceMocks(redisState) {
  let deliveredCode = null;
  mock.method(globalThis, "fetch", async (input, init) => {
    const url = input instanceof Request ? input.url : String(input);
    if (url.startsWith("https://redis.test")) {
      const body = init?.body ?? (input instanceof Request ? await input.clone().text() : "null");
      const parsed = JSON.parse(body);
      const commands = url.endsWith("/pipeline") ? parsed : [parsed];
      const results = commands.map((command) => {
        const operation = String(command[0]).toUpperCase();
        if (operation === "SET") {
          redisState.values.set(command[1], command[2]);
          return { result: "OK" };
        }
        if (operation === "GET") {
          return { result: redisState.values.get(command[1]) ?? null };
        }
        if (operation === "EVAL") {
          const [, script, , key, argument] = command;
          if (script.includes("INCR")) {
            const count = (redisState.counters.get(key) ?? 0) + 1;
            redisState.counters.set(key, count);
            return { result: count };
          }
          if (script.includes("local expected = cjson.decode(ARGV[1])")) {
            const raw = redisState.values.get(key);
            if (!raw?.startsWith("ephemeral:")) return { result: null };
            const state = JSON.parse(raw.slice("ephemeral:".length));
            const expected = JSON.parse(command[4]);
            if (!expected.includes(state.status)) return { result: null };
            Object.assign(state, JSON.parse(command[5]));
            const encoded = `ephemeral:${JSON.stringify(state)}`;
            redisState.values.set(key, encoded);
            return { result: encoded };
          }
          if (script.includes("state.exchangeCode ~= ARGV[2]")) {
            const raw = redisState.values.get(key);
            if (!raw?.startsWith("ephemeral:")) return { result: null };
            const state = JSON.parse(raw.slice("ephemeral:".length));
            if (state.status !== command[4] || state.exchangeCode !== command[5]) return { result: null };
            redisState.values.delete(key);
            return { result: raw };
          }
          if (script.includes("redis.call('GET'")) {
            const matches = redisState.values.get(key) === argument;
            if (matches) redisState.values.delete(key);
            return { result: matches ? 1 : 0 };
          }
          throw new Error("Unexpected Upstash script");
        }
        if (operation === "DEL") {
          redisState.values.delete(command[1]);
          return { result: 1 };
        }
        throw new Error(`Unexpected Upstash command ${command[0]}`);
      });
      return Response.json(url.endsWith("/pipeline") ? results : results[0]);
    }
    if (url === "https://integration.test/internal/mail/verification-code") {
      const bodyText = init?.body ?? (input instanceof Request ? await input.clone().text() : "null");
      const body = JSON.parse(bodyText);
      deliveredCode = body.code;
      return Response.json({ sent: true });
    }
    if (url.startsWith("https://api.weixin.qq.com/sns/jscode2session?")) {
      return Response.json({ openid: "openid-test-user" });
    }
    if (url.startsWith("https://api.weixin.qq.com/cgi-bin/token?")) {
      return Response.json({ access_token: "mock-wechat-access-token", expires_in: 7200 });
    }
    if (url.startsWith("https://api.weixin.qq.com/wxa/getwxacodeunlimit?")) {
      const image = new Uint8Array(128);
      image.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
      return new Response(image, { headers: { "content-type": "image/png" } });
    }
    throw new Error(`Unexpected auth flow fetch: ${url}`);
  });
  return { getDeliveredCode: () => deliveredCode };
}

function request(path, { method = "GET", body, token } = {}) {
  const headers = { "x-dinqor-service-token": serviceToken };
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  return new Request(`https://core.test${path}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

function environment() {
  const redisState = { values: new Map(), counters: new Map(), commands: [] };
  const mail = installServiceMocks(redisState);
  return {
    mail,
    redisState,
    env: {
      DB: createDb(),
      INTERNAL_SERVICE_TOKEN: serviceToken,
      JWT_SECRET: jwtSecret,
      AUTH_PASSWORD_PEPPER: "local-test-password-pepper-at-least-32-bytes",
      JWT_ACCESS_TTL_SECONDS: "600",
      JWT_REFRESH_TTL_SECONDS: "1200",
      UPSTASH_REDIS_REST_URL: "https://redis.test",
      UPSTASH_REDIS_REST_TOKEN: "test-token",
      PLATFORM_INTEGRATION_SERVICE_URL: "https://integration.test",
      PLATFORM_INTEGRATION_SERVICE_TOKEN: "integration-test-token",
      WECHAT_MINIAPP_APP_ID: "mock-app-id",
      WECHAT_MINIAPP_APP_SECRET: "mock-app-secret",
    },
  };
}

afterEach(() => mock.restoreAll());

test("register, login, email reset, and auth-version fencing work end to end", async () => {
  const { env, mail } = environment();
  const registered = await coreWorker.fetch(request("/api/auth/register", {
    method: "POST",
    body: { email: "alice@example.com", password: "initial-pass", name: "Alice" },
  }), env);
  assert.equal(registered.status, 200);
  const registration = await registered.json();
  assert.equal(registration.code, 200);
  assert.equal(registration.data.userInfo.email, "alice@example.com");
  assert.equal(env.DB.pointAccounts.get(env.DB.users[0].id), 2_000_000);
  assert.equal(env.DB.ledger.length, 1);
  assert.equal(env.DB.ledger[0].bizKey, `grant:user:${env.DB.users[0].id}`);
  const oldAccessToken = registration.data.accessToken;

  const wrongPassword = await coreWorker.fetch(request("/api/auth/login", {
    method: "POST",
    body: { type: "password", account: "alice@example.com", password: "wrong-pass" },
  }), env);
  assert.equal((await wrongPassword.json()).code, 1001);

  const sent = await coreWorker.fetch(
    request("/api/auth/code?email=alice%40example.com"),
    env,
  );
  assert.equal(sent.status, 200);
  const code = mail.getDeliveredCode();
  assert.match(code, /^\d{6}$/);

  const reset = await coreWorker.fetch(request("/api/auth/reset-password", {
    method: "POST",
    body: { email: "alice@example.com", code, newPassword: "replacement-pass" },
  }), env);
  assert.equal(reset.status, 200);
  assert.equal((await reset.json()).data, null);
  assert.equal(env.DB.users[0].authVersion, 1);
  assert.equal(await verifyPassword(
    "replacement-pass",
    env.DB.users[0].password,
    env.AUTH_PASSWORD_PEPPER,
  ), true);
  const oldSession = await coreWorker.fetch(request("/api/auth/me", { token: oldAccessToken }), env);
  assert.equal(oldSession.status, 401);

  const oldPasswordLogin = await coreWorker.fetch(request("/api/auth/login", {
    method: "POST",
    body: { type: "password", account: "alice@example.com", password: "initial-pass" },
  }), env);
  assert.equal((await oldPasswordLogin.json()).code, 1001);

  const newPasswordLogin = await coreWorker.fetch(request("/api/auth/login", {
    method: "POST",
    body: { type: "password", account: "alice@example.com", password: "replacement-pass" },
  }), env);
  assert.equal(newPasswordLogin.status, 200);
  const loggedIn = await newPasswordLogin.json();
  assert.equal(loggedIn.code, 200, `${loggedIn.code} ${loggedIn.message}`);
  assert.equal(loggedIn.data.userInfo.username, "alice@example.com");
  assert.equal((await coreWorker.fetch(request("/api/auth/me", {
    token: loggedIn.data.accessToken,
  }), env)).status, 200);
});

test("legacy BCrypt users can reset their password and sign in with the new hash", async () => {
  const { env, mail } = environment();
  env.DB.seedUser({
    username: "legacy@example.com",
    email: "legacy@example.com",
    password: "$2a$10$legacy-bcrypt-hash-from-java",
    nickname: "Legacy User",
  });

  const legacyLogin = await coreWorker.fetch(request("/api/auth/login", {
    method: "POST",
    body: { type: "password", account: "legacy@example.com", password: "old-password" },
  }), env);
  assert.equal((await legacyLogin.json()).code, 1001);

  const codeResponse = await coreWorker.fetch(
    request("/api/auth/code?email=legacy%40example.com"),
    env,
  );
  assert.equal(codeResponse.status, 200);
  const resetResponse = await coreWorker.fetch(request("/api/auth/reset-password", {
    method: "POST",
    body: {
      email: "legacy@example.com",
      code: mail.getDeliveredCode(),
      newPassword: "new-password",
    },
  }), env);
  assert.equal(resetResponse.status, 200);

  const newLogin = await coreWorker.fetch(request("/api/auth/login", {
    method: "POST",
    body: { type: "password", account: "legacy@example.com", password: "new-password" },
  }), env);
  const payload = await newLogin.json();
  assert.equal(newLogin.status, 200);
  assert.equal(payload.code, 200);
  assert.equal(payload.data.userInfo.email, "legacy@example.com");
});

test("password changes require the current password and invalidate existing sessions", async () => {
  const { env } = environment();
  const registered = await coreWorker.fetch(request("/api/auth/register", {
    method: "POST",
    body: { email: "change@example.com", password: "initial-pass", name: "Change" },
  }), env);
  const registration = await registered.json();
  assert.equal(registration.code, 200);

  const changed = await coreWorker.fetch(request("/api/auth/password", {
    method: "PUT",
    token: registration.data.accessToken,
    body: { currentPassword: "initial-pass", newPassword: "replacement-pass" },
  }), env);
  assert.equal(changed.status, 200);
  assert.equal((await changed.json()).code, 200);
  assert.equal(env.DB.users[0].authVersion, 1);

  const oldSession = await coreWorker.fetch(request("/api/auth/me", {
    token: registration.data.accessToken,
  }), env);
  assert.equal(oldSession.status, 401);

  const newLogin = await coreWorker.fetch(request("/api/auth/login", {
    method: "POST",
    body: { type: "password", account: "change@example.com", password: "replacement-pass" },
  }), env);
  assert.equal((await newLogin.json()).code, 200);
});

test("WeChat first login can skip binding and receives the registration grant", async () => {
  const { env } = environment();
  const firstLogin = await coreWorker.fetch(request("/api/auth/wechat-login", {
    method: "POST",
    body: { code: "mock-login-code" },
  }), env);
  assert.equal(firstLogin.status, 200);
  const pending = await firstLogin.json();
  assert.equal(pending.data.pendingBind, true);
  assert.equal(pending.data.userInfo, null);

  const skipped = await coreWorker.fetch(request("/api/auth/wechat-skip", {
    method: "POST",
    body: { pendingBindToken: pending.data.pendingBindToken },
  }), env);
  assert.equal(skipped.status, 200, await skipped.clone().text());
  const signedIn = await skipped.json();
  assert.equal(signedIn.code, 200);
  assert.equal(signedIn.data.needsEmailBinding, true);
  assert.equal(env.DB.users[0].openid, "openid-test-user");
  assert.equal(env.DB.pointAccounts.get(env.DB.users[0].id), 2_000_000);
});

test("QR login confirms an active mini-program session and exchanges once", async () => {
  const { env } = environment();
  const user = env.DB.seedUser({ username: "qr-user", nickname: "QR User" });
  const miniProgramToken = await signJwtToken(
    String(user.id), jwtSecret, { role: "USER", ver: 0, type: "access" }, 600,
  );
  const created = await coreWorker.fetch(request("/api/auth/qr/create", { method: "POST" }), env);
  assert.equal(created.status, 200);
  const qr = await created.json();
  assert.equal(qr.code, 200);
  assert.match(qr.data.qrCode, /^data:image\/png;base64,/);

  const confirmed = await coreWorker.fetch(request(
    `/api/auth/qr/confirm?ticket=${qr.data.ticket}`,
    { method: "POST", token: miniProgramToken },
  ), env);
  assert.equal(confirmed.status, 200, await confirmed.clone().text());
  const state = await coreWorker.fetch(request(`/api/auth/qr/status?ticket=${qr.data.ticket}`), env);
  const qrStatus = await state.json();
  assert.equal(qrStatus.data.status, "CONFIRMED");
  const exchangeCode = qrStatus.data.exchangeCode;

  const exchanged = await coreWorker.fetch(request(
    `/api/auth/qr/exchange?ticket=${qr.data.ticket}&exchangeCode=${exchangeCode}`,
    { method: "POST" },
  ), env);
  assert.equal(exchanged.status, 200);
  assert.equal((await exchanged.json()).code, 200);
  const replay = await coreWorker.fetch(request(
    `/api/auth/qr/exchange?ticket=${qr.data.ticket}&exchangeCode=${exchangeCode}`,
    { method: "POST" },
  ), env);
  assert.equal(replay.status, 400);
});

test("administrator user routes paginate, protect roles, and invalidate disabled sessions", async () => {
  const { env } = environment();
  const admin = env.DB.seedUser({ username: "admin", role: "ADMIN" });
  const target = env.DB.seedUser({ username: "member", email: "member@example.com" });
  const ordinary = env.DB.seedUser({ username: "ordinary" });
  const adminToken = await signJwtToken(String(admin.id), jwtSecret, { role: "ADMIN", ver: 0, type: "access" }, 600);
  const targetToken = await signJwtToken(String(target.id), jwtSecret, { role: "USER", ver: 0, type: "access" }, 600);
  const ordinaryToken = await signJwtToken(String(ordinary.id), jwtSecret, { role: "USER", ver: 0, type: "access" }, 600);

  const denied = await coreWorker.fetch(request("/api/admin/users", { token: ordinaryToken }), env);
  assert.equal(denied.status, 403);
  const list = await coreWorker.fetch(request("/api/admin/users?page=1&size=2", { token: adminToken }), env);
  const page = await list.json();
  assert.equal(list.status, 200);
  assert.equal(page.data.total, 3);
  assert.equal(page.data.records.length, 2);
  assert.equal(page.data.hasMore, true);

  const disabled = await coreWorker.fetch(request(`/api/admin/users/${target.id}/status`, {
    method: "PATCH", token: adminToken, body: { status: 0 },
  }), env);
  assert.equal(disabled.status, 200);
  assert.equal((await disabled.json()).data.status, 0);
  assert.equal(env.DB.users.find((user) => user.id === target.id).authVersion, 1);
  const staleSession = await coreWorker.fetch(request("/api/auth/me", { token: targetToken }), env);
  assert.equal(staleSession.status, 401);
});
