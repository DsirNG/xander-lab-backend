import {
  API_BASE_URL,
  CONNECTOR_ORIGINS,
  PLATFORM_COOKIE_DOMAINS,
  PLATFORM_URLS,
  SESSION_COOKIE_NAMES,
} from "./config.js";

const TASKS_KEY = "authorizationTasks";
const inFlightTasks = new Set();
const SUPPORTED_PLATFORMS = Object.keys(PLATFORM_URLS);

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "PING") {
    sendResponse({ available: true });
    return false;
  }
  if (message?.type === "READ_WECHAT_PROFILE") {
    const pageUrl = sender.url ? new URL(sender.url) : null;
    if (!pageUrl || !CONNECTOR_ORIGINS.has(pageUrl.origin) || !Number.isInteger(sender.tab?.id)) {
      sendResponse({ ok: false, errorCode: "PROFILE_READ_NOT_ALLOWED" });
      return false;
    }
    readWechatProfileFromAccountTab()
      .then((profile) => sendResponse(profile
        ? { ok: true, profile }
        : { ok: false, errorCode: "WECHAT_PROFILE_NOT_FOUND" }))
      .catch(() => sendResponse({ ok: false, errorCode: "WECHAT_PROFILE_NOT_FOUND" }));
    return true;
  }
  if (message?.type === "CANCEL") {
    const pageUrl = sender.url ? new URL(sender.url) : null;
    if (pageUrl && CONNECTOR_ORIGINS.has(pageUrl.origin) && /^[0-9a-f-]{36}$/i.test(message.taskId)) {
      readTasks().then((tasks) => {
        const task = tasks.find((entry) => entry.taskId === message.taskId);
        if (task) return removeTask(task, true);
      });
    }
    return false;
  }
  if (message?.type !== "START") return false;

  const pageUrl = sender.url ? new URL(sender.url) : null;
  if (!pageUrl || !CONNECTOR_ORIGINS.has(pageUrl.origin)
    || !Number.isInteger(sender.tab?.id)
    || !SUPPORTED_PLATFORMS.includes(message.platform)
    || !/^[0-9a-f-]{36}$/i.test(message.taskId)
    || !/^[A-Za-z0-9_-]{43}$/.test(message.transferToken)) {
    sendResponse({ ok: false });
    return false;
  }

  beginAuthorization(message, sender.tab.id).then(() => sendResponse({ ok: true })).catch(() => sendResponse({ ok: false }));
  return true;
});

chrome.cookies.onChanged.addListener(({ cookie, removed }) => {
  if (removed) return;
  const platform = platformForCookie(cookie);
  if (platform && SESSION_COOKIE_NAMES[platform].includes(cookie.name)) {
    void checkPlatformTasks(platform);
  }
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "authorization-check") void checkPlatformTasks();
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status !== "complete") return;
  void readTasks().then((tasks) => {
    const task = tasks.find((entry) => entry.tabId === tabId);
    if (task) return checkPlatformTasks(task.platform);
  }).catch(() => {});
});

async function beginAuthorization(task, connectorTabId) {
  const active = await readTasks();
  const previous = active.filter((entry) => entry.platform === task.platform);
  for (const entry of previous) await removeTask(entry, true);

  const tab = await chrome.tabs.create({ url: PLATFORM_URLS[task.platform], active: true });
  const authorizationTask = {
    ...task,
    tabId: tab.id,
    connectorTabId,
    expiresAt: Date.now() + 5 * 60_000,
  };
  active.push(authorizationTask);
  await chrome.storage.local.set({ [TASKS_KEY]: active });
  chrome.alarms.create("authorization-check", { periodInMinutes: 0.5 });
  notifyConnector(authorizationTask, "WAITING_FOR_SESSION");
  void checkPlatformTasks(task.platform).catch(() => {});
}

async function checkPlatformTasks(platformFilter) {
  const tasks = await readTasks();
  for (const task of tasks) {
    if (platformFilter && task.platform !== platformFilter) continue;
    if (inFlightTasks.has(task.taskId)) continue;
    if (task.expiresAt <= Date.now()) {
      notifyConnector(task, "FAILED", "TASK_EXPIRED");
      await removeTask(task, true);
      continue;
    }
    inFlightTasks.add(task.taskId);
    try {
      const platformOrigin = new URL(PLATFORM_URLS[task.platform]).origin;
      // Chromium checks non-Secure cookies against an HTTP origin even on HTTPS pages.
      // A domain query silently omits them when only HTTPS access is granted.
      const origins = task.platform === "csdn"
        ? ["https://*.csdn.net/*", "http://*.csdn.net/*"]
        : [`${platformOrigin}/*`];
      const hasPlatformPermission = await chrome.permissions.contains({ origins });
      if (!hasPlatformPermission) {
        logAuthorization(task.platform, "permission-check", "SITE_PERMISSION_REQUIRED");
        notifyConnector(task, "FAILED", "SITE_PERMISSION_REQUIRED");
        await removeTask(task, false);
        continue;
      }

      // CSDN identity cookies may belong to a different subdomain from the publishing cookies.
      // Query the whole authorized domain in the platform tab's cookie store.
      const csdnSession = task.platform === "csdn"
        ? await getCsdnCookies(await getCookieContext(task.tabId))
        : null;
      const cookies = csdnSession?.cookies
        ?? await chrome.cookies.getAll({ url: PLATFORM_URLS[task.platform] });
      const values = new Map(cookies.map((cookie) => [cookie.name, cookie.value]));
      const hasSession = SESSION_COOKIE_NAMES[task.platform].some((name) => Boolean(values.get(name)));
      if (!hasSession) {
        const errorCode = task.platform === "csdn" ? "SESSION_COOKIE_MISSING" : null;
        const diagnostic = {
          sitePermissionGranted: true,
          sessionCookieFound: false,
          cookieCount: cookies.length,
        };
        if (csdnSession) {
          diagnostic.cookieScopes = {
            ...csdnSession.queryCounts,
            cookieStoreMatched: csdnSession.cookieStoreMatched,
            partitionKeyAvailable: csdnSession.partitionKeyAvailable,
          };
        }
        logAuthorization(task.platform, "cookie-check", errorCode ?? "SESSION_COOKIE_MISSING", {
          ...diagnostic,
          cookieScopes: csdnSession ? {
            ...csdnSession.queryCounts,
            cookieStoreMatched: csdnSession.cookieStoreMatched,
            partitionKeyAvailable: csdnSession.partitionKeyAvailable,
          } : undefined,
        });
        notifyConnector(task, "WAITING_FOR_SESSION", errorCode, diagnostic);
        continue;
      }
      logAuthorization(task.platform, "cookie-check", "SESSION_COOKIE_FOUND", {
        sitePermissionGranted: true,
        sessionCookieFound: true,
        cookieCount: cookies.length,
        cookieScopes: csdnSession ? {
          ...csdnSession.queryCounts,
          cookieStoreMatched: csdnSession.cookieStoreMatched,
          partitionKeyAvailable: csdnSession.partitionKeyAvailable,
        } : undefined,
      });

      const cookie = cookies
        .filter((entry) => entry.name && !/[\r\n;=]/.test(entry.name) && !/[\r\n;]/.test(entry.value))
        .sort((left, right) => left.name.localeCompare(right.name))
        .map((entry) => `${entry.name}=${entry.value}`)
        .join("; ");
      const accountName = task.platform === "csdn"
        ? csdnSession.accountName || values.get("UserName") || null
        : null;
      const credential = task.platform === "csdn"
        ? { cookie, username: accountName || "" }
        : task.platform === "juejin"
          ? {
          cookie,
          csrfToken: values.get("passport_csrf_token") || "",
          uuid: values.get("uuid") || "",
          sessionKey: values.get("sessionid_ss") || values.get("sessionid") || values.get("sid_tt") || "",
        }
          : { cookie };

      const accountProfile = task.platform === "wechat_mp"
        ? await readWechatProfileFromAccountTab()
        : null;
      const profileAccountName = accountProfile?.nickname || accountName;

      notifyConnector(task, "SUBMITTING", null, { sessionCookieFound: true, cookieCount: cookies.length });
      let response;
      try {
        response = await fetch(
          `${API_BASE_URL}/api/publishing/${task.platform}/authorization/extension/${task.taskId}/complete`,
          {
            method: "POST",
            headers: {
              "content-type": "application/json",
              authorization: `Bearer ${task.transferToken}`,
            },
            body: JSON.stringify({ credential, accountName: profileAccountName, accountProfile }),
            cache: "no-store",
          },
        );
      } catch {
        logAuthorization(task.platform, "complete", "NETWORK_ERROR");
        notifyConnector(task, "RETRYING", "NETWORK_ERROR");
        continue;
      }

      if (response.ok) {
        const result = await readCompletionResult(response);
        logAuthorization(task.platform, "complete", "COMPLETED", {
          httpStatus: response.status,
          responseCode: result.code,
        });
        notifyConnector(task, "COMPLETED", null, {
          httpStatus: response.status,
          responseCode: result.code,
          sessionCookieFound: true,
          cookieCount: cookies.length,
        });
        await removeTask(task, true);
        await returnToConnector(task);
      } else if (response.status === 400) {
        const result = await readCompletionResult(response);
        logAuthorization(task.platform, "complete", "SESSION_NOT_RECOGNIZED", {
          httpStatus: response.status,
          responseCode: result.code,
        });
        notifyConnector(task, "FAILED", "SESSION_NOT_RECOGNIZED", {
          httpStatus: response.status,
          responseCode: result.code,
          sessionCookieFound: true,
          cookieCount: cookies.length,
        });
        await removeTask(task, false);
      } else if ([401, 409, 410].includes(response.status)) {
        const result = await readCompletionResult(response);
        const errorCode = response.status === 401 ? "HANDOFF_INVALID"
          : response.status === 409 ? "TASK_CONFLICT" : "TASK_EXPIRED";
        logAuthorization(task.platform, "complete", errorCode, {
          httpStatus: response.status,
          responseCode: result.code,
        });
        notifyConnector(task, "FAILED", errorCode, {
          httpStatus: response.status,
          responseCode: result.code,
          sessionCookieFound: true,
          cookieCount: cookies.length,
        });
        await removeTask(task, true);
      } else if (response.status >= 500 || response.status === 429) {
        const result = await readCompletionResult(response);
        logAuthorization(task.platform, "complete", "SERVICE_UNAVAILABLE", {
          httpStatus: response.status,
          responseCode: result.code,
        });
        notifyConnector(task, "RETRYING", "SERVICE_UNAVAILABLE", {
          httpStatus: response.status,
          responseCode: result.code,
          sessionCookieFound: true,
          cookieCount: cookies.length,
        });
      } else {
        const result = await readCompletionResult(response);
        logAuthorization(task.platform, "complete", "SUBMISSION_FAILED", {
          httpStatus: response.status,
          responseCode: result.code,
        });
        notifyConnector(task, "FAILED", "SUBMISSION_FAILED", {
          httpStatus: response.status,
          responseCode: result.code,
          sessionCookieFound: true,
          cookieCount: cookies.length,
        });
        await removeTask(task, false);
      }
    } catch {
      logAuthorization(task.platform, "cookie-check", "COOKIE_ACCESS_FAILED");
      notifyConnector(task, "FAILED", "COOKIE_ACCESS_FAILED");
      await removeTask(task, false);
    } finally {
      inFlightTasks.delete(task.taskId);
    }
  }
}

function notifyConnector(task, state, errorCode, diagnostic) {
  if (!Number.isInteger(task.connectorTabId)) return;
  const message = { type: "AUTHORIZATION_PROGRESS", taskId: task.taskId, state };
  if (errorCode) message.errorCode = errorCode;
  if (diagnostic) message.diagnostic = diagnostic;
  try {
    const pending = chrome.tabs.sendMessage(task.connectorTabId, message);
    if (pending && typeof pending.catch === "function") pending.catch(() => {});
  } catch {
    // The DinQorAI tab may have been closed; never log credentials or handoff tokens.
  }
}

async function readWechatProfile(tabId) {
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      const profile = await chrome.tabs.sendMessage(tabId, { type: "READ_WECHAT_ACCOUNT_PROFILE" }, { frameId: 0 });
      if (!profile || typeof profile !== "object") return null;
      const nickname = typeof profile.nickname === "string" ? profile.nickname.trim().slice(0, 200) : "";
      let avatarUrl = null;
      if (typeof profile.avatarUrl === "string") {
        try {
          const url = new URL(profile.avatarUrl);
          if (url.protocol === "https:" && ["wx.qlogo.cn", "thirdwx.qlogo.cn", "mmbiz.qpic.cn"].includes(url.hostname)) {
            avatarUrl = url.href;
          }
        } catch { /* An optional avatar must not invalidate a successful binding. */ }
      }
      return nickname ? { nickname, avatarUrl } : null;
    } catch {
      if (attempt === 3) return null;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  return null;
}

async function readWechatProfileFromAccountTab() {
  const tabs = await chrome.tabs.query({ url: "https://mp.weixin.qq.com/*" });
  const candidates = tabs.filter((entry) => Number.isInteger(entry.id))
    .sort((left, right) => Number(right.active === true) - Number(left.active === true)
      || (right.lastAccessed ?? 0) - (left.lastAccessed ?? 0));
  const existingProfiles = await Promise.all(candidates.slice(0, 4).map((entry) => readWechatProfile(entry.id)));
  const existingProfile = existingProfiles.find(Boolean);
  if (existingProfile) return existingProfile;

  let tab;
  let createdTabId = null;
  tab = await chrome.tabs.create({ url: PLATFORM_URLS.wechat_mp, active: false });
  createdTabId = tab.id;
  try {
    if (!Number.isInteger(tab.id)) return null;
    for (let attempt = 0; attempt < 30; attempt += 1) {
      let currentTab;
      try { currentTab = await chrome.tabs.get(tab.id); } catch { return null; }
      if (currentTab.status === "complete") break;
      if (attempt === 29) return null;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const profile = await readWechatProfile(tab.id);
      if (profile) return profile;
      if (attempt < 2) await new Promise((resolve) => setTimeout(resolve, 500));
    }
    return null;
  } finally {
    if (Number.isInteger(createdTabId)) {
      try { await chrome.tabs.remove(createdTabId); } catch { /* The account tab may already be closed. */ }
    }
  }
}

function logAuthorization(platform, phase, result, diagnostic = {}) {
  // Keep browser-console diagnostics useful without ever exposing cookie values or handoff tokens.
  console.info("[DinQor Connector] authorization", { platform, phase, result, ...diagnostic });
}

async function readCompletionResult(response) {
  try {
    const body = await response.clone().json();
    return { code: Number.isInteger(body?.code) ? body.code : null };
  } catch {
    return { code: null };
  }
}

async function returnToConnector(task) {
  if (!Number.isInteger(task.connectorTabId)) return;
  try {
    // Closing a tab alone lets the browser choose an unrelated neighboring tab.
    const tab = await chrome.tabs.update(task.connectorTabId, { active: true });
    if (Number.isInteger(tab.windowId)) {
      await chrome.windows.update(tab.windowId, { focused: true });
    }
  } catch {
    // A closed originating tab/window must not turn a completed binding into failure.
  }
}

async function removeTask(task, closeTab) {
  const remaining = (await readTasks()).filter((entry) => entry.taskId !== task.taskId);
  await chrome.storage.local.set({ [TASKS_KEY]: remaining });
  if (closeTab && Number.isInteger(task.tabId)) {
    try { await chrome.tabs.remove(task.tabId); } catch { /* tab may already be closed */ }
  }
  if (remaining.length === 0) await chrome.alarms.clear("authorization-check");
}

async function readTasks() {
  const stored = await chrome.storage.local.get(TASKS_KEY);
  return Array.isArray(stored[TASKS_KEY]) ? stored[TASKS_KEY] : [];
}

async function getCookieContext(tabId) {
  let storeId = null;
  try {
    if (Number.isInteger(tabId)) {
      const stores = await chrome.cookies.getAllCookieStores();
      storeId = stores.find((store) => store.tabIds.includes(tabId))?.id ?? null;
    }
  } catch {
    // Fall back to the default store when the browser cannot identify this tab's store.
  }

  let partitionKey = null;
  try {
    if (Number.isInteger(tabId) && typeof chrome.cookies.getPartitionKey === "function") {
      const details = await chrome.cookies.getPartitionKey({ tabId });
      partitionKey = details?.partitionKey ?? null;
    }
  } catch {
    // Older browsers and tabs without a partition key use unpartitioned cookies.
  }

  return { storeId, partitionKey };
}

async function getCsdnCookies({ storeId, partitionKey } = {}) {
  const query = (filters, partitioned = false) => chrome.cookies.getAll({
    ...filters,
    ...(storeId ? { storeId } : {}),
    ...(partitioned && partitionKey ? { partitionKey } : {}),
  });
  const domainCookies = await query({ domain: "csdn.net" });
  let partitionedCookies = [];
  if (partitionKey) {
    partitionedCookies = await query({ domain: "csdn.net" }, true).catch(() => []);
  }
  const csdnCookies = new Map();
  for (const cookie of [...domainCookies, ...partitionedCookies]) {
    const identity = [cookie.storeId, cookie.domain, cookie.path, cookie.name].join("\u0000");
    csdnCookies.set(identity, cookie);
  }
  const cookies = [...csdnCookies.values()];
  const partitionedTokenCount = new Set(partitionedCookies
    .filter((cookie) => cookie.name === "UserToken" && cookie.value)
    .map((cookie) => [cookie.storeId, cookie.domain, cookie.path, cookie.name].join("\u0000"))).size;
  const identityHints = new Set(cookies
    .filter((cookie) => ["UserName", "AU", "UserNick"].includes(cookie.name) && cookie.value)
    .map((cookie) => cookie.name)).size;
  const accountName = cookies.find((cookie) => cookie.name === "UserName" && cookie.value)?.value ?? null;
  return {
    cookies,
    accountName,
    queryCounts: {
      domain: domainCookies.length,
      userToken: domainCookies.filter((cookie) => cookie.name === "UserToken" && cookie.value).length,
      username: cookies.filter((cookie) => cookie.name === "UserName" && cookie.value).length,
      partitioned: partitionedCookies.length,
      partitionedToken: partitionedTokenCount,
      identityHints,
    },
    cookieStoreMatched: Boolean(storeId),
    partitionKeyAvailable: Boolean(partitionKey),
  };
}

function platformForCookie(cookie) {
  const domain = cookie.domain.replace(/^\./, "");
  for (const [platform, domains] of Object.entries(PLATFORM_COOKIE_DOMAINS)) {
    if (domains.some((allowed) => domain === allowed || domain.endsWith(`.${allowed}`))) return platform;
  }
  return null;
}
