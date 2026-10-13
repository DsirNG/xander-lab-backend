import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";

const manifest = JSON.parse(await readFile(new URL("../manifest.json", import.meta.url), "utf8"));

function canReadCookie(cookie) {
  const scheme = cookie.secure ? "https" : "http";
  const host = cookie.domain.replace(/^\./, "");
  return manifest.host_permissions.some((pattern) => {
    const url = new URL(pattern);
    return url.protocol === `${scheme}:` && (url.hostname === host
      || (url.hostname.startsWith("*.") && (host === url.hostname.slice(2)
        || host.endsWith(url.hostname.slice(1)))));
  });
}

test("CSDN permissions cover both Secure and non-Secure login cookies", () => {
  for (const domain of [".csdn.net", "mp.csdn.net", "passport.csdn.net"]) {
    assert.equal(canReadCookie({ domain, secure: false }), true);
    assert.equal(canReadCookie({ domain, secure: true }), true);
  }
  assert.equal(canReadCookie({ domain: "unrelated.example", secure: false }), false);
  assert.equal(canReadCookie({ domain: "mp.weixin.qq.com", secure: false }), true);
  assert.equal(canReadCookie({ domain: "mp.weixin.qq.com", secure: true }), true);
});

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

async function waitFor(predicate) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) return;
    await tick();
  }
  assert.fail("timed out waiting for extension progress");
}

test("reports cookie, permission, and submission progress to the originating DinQor tab", async (context) => {
  const originalConsole = globalThis.console;
  context.after(() => { globalThis.console = originalConsole; });
  const events = new Map();
  const makeEvent = (name) => ({
    addListener(listener) {
      const listeners = events.get(name) || [];
      listeners.push(listener);
      events.set(name, listeners);
    },
  });
  const storage = {};
  const progress = [];
  const closedTabs = [];
  let accountTabs = [];
  const activatedTabs = [];
  const focusedWindows = [];
  let cookies = [];
  const cookieQueries = [];
  const submittedBodies = [];
  const diagnosticLogs = [];
  let platformPermission = true;
  let completionStatus = 200;
  let nextTabId = 200;
  const platformPartitionKey = { topLevelSite: "https://csdn.net", hasCrossSiteAncestor: false };
  const chromeApi = {
    runtime: { onMessage: makeEvent("message") },
    cookies: {
      onChanged: makeEvent("cookie"),
      getAll: async (query) => {
        cookieQueries.push(query);
        const queryUrl = query.url ? new URL(query.url) : null;
        return cookies.filter((entry) => {
          if (!canReadCookie(entry)) return false;
          const cookieDomain = entry.domain.replace(/^\./, "");
          if (query.name && entry.name !== query.name) return false;
          if (query.storeId && (entry.storeId ?? "store-platform") !== query.storeId) return false;
          if (query.partitionKey) {
            if (JSON.stringify(entry.partitionKey) !== JSON.stringify(query.partitionKey)) return false;
          } else if (entry.partitionKey) return false;
          if (query.domain) {
            return cookieDomain === query.domain || cookieDomain.endsWith(`.${query.domain}`);
          }
          const domainMatches = entry.hostOnly
            ? queryUrl.hostname === cookieDomain
            : queryUrl.hostname === cookieDomain || queryUrl.hostname.endsWith(`.${cookieDomain}`);
          const cookiePath = entry.path || "/";
          const pathMatches = queryUrl.pathname === cookiePath
            || queryUrl.pathname.startsWith(cookiePath.endsWith("/") ? cookiePath : `${cookiePath}/`);
          return domainMatches && pathMatches;
        });
      },
      getAllCookieStores: async () => [{ id: "store-platform", tabIds: [200, 201, 202] }],
      getPartitionKey: async () => ({ partitionKey: platformPartitionKey }),
    },
    permissions: { contains: async () => platformPermission },
    tabs: {
      onUpdated: makeEvent("tabUpdated"),
      query: async () => accountTabs,
      get: async (tabId) => ({ id: tabId, status: "complete" }),
      create: async () => ({ id: nextTabId++ }),
      remove: async (tabId) => closedTabs.push(tabId),
      update: async (tabId, options) => {
        activatedTabs.push({ tabId, options });
        return { id: tabId, windowId: 17 };
      },
      sendMessage: async (tabId, message) => {
        if (message.type === "READ_WECHAT_ACCOUNT_PROFILE") {
          return { nickname: "测试公众号", avatarUrl: "https://wx.qlogo.cn/mmhead/test/0" };
        }
        progress.push({ tabId, ...message });
        return undefined;
      },
    },
    windows: { update: async (windowId, options) => focusedWindows.push({ windowId, options }) },
    storage: {
      local: {
        get: async (key) => ({ [key]: storage[key] }),
        set: async (value) => Object.assign(storage, value),
      },
    },
    alarms: { create() {}, clear: async () => true, onAlarm: makeEvent("alarm") },
  };

  globalThis.chrome = chromeApi;
  globalThis.console = { info: (...values) => diagnosticLogs.push(values) };
  globalThis.fetch = async (_url, options) => {
    submittedBodies.push(JSON.parse(options.body));
    const code = completionStatus;
    return new Response(JSON.stringify({ code, message: code === 200 ? "success" : "invalid session", data: null }), {
      status: completionStatus,
    });
  };
  await import(`../background.js?test=${Date.now()}`);

  const startTask = async (taskId, platform = "csdn") => {
    const responses = [];
    const listener = events.get("message")[0];
    const keptOpen = listener({
      type: "START", platform, taskId, transferToken: "A".repeat(43),
    }, { url: "http://localhost:3000/profile", tab: { id: 55 } }, (value) => responses.push(value));
    assert.equal(keptOpen, true);
    await waitFor(() => responses.length === 1);
    assert.deepEqual(responses[0], { ok: true });
  };

  const taskId = "123e4567-e89b-42d3-a456-426614174000";
  cookies = [
    { name: "UserName", value: "test-user", domain: "csdn.net", hostOnly: true, path: "/", storeId: "store-platform" },
    { name: "AU", value: "test-au", domain: "csdn.net", hostOnly: true, path: "/", storeId: "store-platform" },
    { name: "UserNick", value: "test-nick", domain: "csdn.net", hostOnly: true, path: "/", storeId: "store-platform" },
  ];
  await startTask(taskId);
  await waitFor(() => progress.some((entry) => entry.taskId === taskId && entry.state === "WAITING_FOR_SESSION"));
  const missingSession = progress.find((entry) => entry.taskId === taskId
    && entry.errorCode === "SESSION_COOKIE_MISSING"
    && entry.diagnostic?.sitePermissionGranted === true
    && entry.diagnostic?.sessionCookieFound === false);
  assert.ok(missingSession);
  assert.equal(missingSession.diagnostic.cookieScopes.domain, 3);
  assert.equal(missingSession.diagnostic.cookieScopes.userToken, 0);
  assert.equal(missingSession.diagnostic.cookieScopes.username, 1);
  assert.equal(missingSession.diagnostic.cookieScopes.partitioned, 0);
  assert.equal(missingSession.diagnostic.cookieScopes.partitionedToken, 0);
  assert.equal(missingSession.diagnostic.cookieScopes.identityHints, 3);
  assert.equal(missingSession.diagnostic.cookieScopes.cookieStoreMatched, true);
  assert.equal(missingSession.diagnostic.cookieScopes.partitionKeyAvailable, true);

  // UserToken can be scoped to a CSDN subdomain that the publishing URL queries do not cover.
  cookies = [
    { name: "UserName", value: "test-user", domain: "csdn.net", hostOnly: true, path: "/", storeId: "store-platform" },
    { name: "shared", value: "cookie", domain: ".csdn.net", hostOnly: false, path: "/", storeId: "store-platform" },
    {
      name: "UserToken", value: "test-cookie", domain: "bizapi.csdn.net", hostOnly: true,
      path: "/download-console-api", storeId: "store-platform", partitionKey: platformPartitionKey,
    },
  ];
  // Reloading an already signed-in tab must recheck even when no cookie changes.
  for (const listener of events.get("tabUpdated")) {
    listener(200, { status: "complete" });
  }
  await waitFor(() => progress.some((entry) => entry.taskId === taskId && entry.state === "COMPLETED"));
  assert.ok(progress.some((entry) => entry.taskId === taskId && entry.state === "SUBMITTING"));
  const completed = progress.find((entry) => entry.taskId === taskId && entry.state === "COMPLETED");
  assert.equal(completed.diagnostic.httpStatus, 200);
  assert.equal(completed.diagnostic.responseCode, 200);
  assert.ok(cookieQueries.some((query) => query.domain === "csdn.net" && query.storeId === "store-platform"));
  assert.ok(!cookieQueries.some((query) => query.url?.includes("download-console-api")));
  assert.ok(cookieQueries.some((query) => query.domain === "csdn.net"
    && query.partitionKey?.topLevelSite === platformPartitionKey.topLevelSite
    && query.storeId === "store-platform"));
  const submittedCookies = submittedBodies[0].credential.cookie.split("; ");
  assert.equal(submittedCookies.length, 3);
  assert.deepEqual(new Set(submittedCookies), new Set([
    "UserName=test-user", "UserToken=test-cookie", "shared=cookie",
  ]));
  assert.equal(submittedBodies[0].accountName, "test-user");
  const serializedLogs = JSON.stringify(diagnosticLogs);
  assert.doesNotMatch(serializedLogs, /test-cookie|test-au|test-nick|A{43}|test-user/);
  assert.match(serializedLogs, /SESSION_COOKIE_FOUND/);
  assert.match(serializedLogs, /COMPLETED/);
  const foundLog = diagnosticLogs.find(([, entry]) => entry.result === "SESSION_COOKIE_FOUND");
  assert.equal(foundLog[1].cookieScopes.cookieStoreMatched, true);
  assert.equal(foundLog[1].cookieScopes.partitionKeyAvailable, true);
  assert.equal(foundLog[1].cookieScopes.partitionedToken, 1);
  assert.deepEqual(storage.authorizationTasks, []);
  assert.deepEqual(closedTabs, [200]);
  await waitFor(() => focusedWindows.length === 1);
  assert.deepEqual(activatedTabs, [{ tabId: 55, options: { active: true } }]);
  assert.deepEqual(focusedWindows, [{ windowId: 17, options: { focused: true } }]);

  completionStatus = 400;
  const rejectedTaskId = "123e4567-e89b-42d3-a456-426614174001";
  await startTask(rejectedTaskId);
  await waitFor(() => progress.some((entry) => entry.taskId === rejectedTaskId && entry.errorCode === "SESSION_NOT_RECOGNIZED"));
  const rejected = progress.find((entry) => entry.taskId === rejectedTaskId && entry.errorCode === "SESSION_NOT_RECOGNIZED");
  assert.equal(rejected.diagnostic.httpStatus, 400);
  assert.equal(rejected.diagnostic.responseCode, 400);
  assert.equal(rejected.diagnostic.sessionCookieFound, true);
  assert.equal(storage.authorizationTasks.length, 0);
  assert.deepEqual(closedTabs, [200]);

  platformPermission = false;
  const permissionTaskId = "123e4567-e89b-42d3-a456-426614174002";
  await startTask(permissionTaskId);
  await waitFor(() => progress.some((entry) => entry.taskId === permissionTaskId && entry.errorCode === "SITE_PERMISSION_REQUIRED"));
  assert.equal(storage.authorizationTasks.length, 0);

  platformPermission = true;
  completionStatus = 200;
  cookies = [{ name: "slave_user", value: "private-cookie", domain: "mp.weixin.qq.com", hostOnly: true, path: "/", storeId: "store-platform" }];
  const wechatTaskId = "123e4567-e89b-42d3-a456-426614174003";
  await startTask(wechatTaskId, "wechat_mp");
  for (const listener of events.get("tabUpdated")) listener(203, { status: "complete" });
  await waitFor(() => progress.some((entry) => entry.taskId === wechatTaskId && entry.state === "COMPLETED"));
  assert.equal(submittedBodies.at(-1).accountName, "测试公众号");
  assert.deepEqual(submittedBodies.at(-1).accountProfile, {
    nickname: "测试公众号", avatarUrl: "https://wx.qlogo.cn/mmhead/test/0",
  });

  accountTabs = [{ id: 300 }];
  const profileResponses = [];
  const readResult = events.get("message")[0]({ type: "READ_WECHAT_PROFILE" }, {
    url: "http://localhost:3000/profile", tab: { id: 55 },
  }, (reply) => profileResponses.push(reply));
  assert.equal(readResult, true);
  await waitFor(() => profileResponses.length === 1);
  assert.deepEqual(profileResponses[0], {
    ok: true,
    profile: { nickname: "测试公众号", avatarUrl: "https://wx.qlogo.cn/mmhead/test/0" },
  });
});
