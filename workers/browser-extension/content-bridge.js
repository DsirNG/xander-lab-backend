(() => {
  const channel = "dinqorai-platform-connector";
  const taskIdPattern = /^[0-9a-f-]{36}$/i;
  const supportedPlatforms = [
    "csdn", "juejin", "wechat_mp", "weibo", "zhihu", "baijiahao", "cnblogs",
    "douyin", "xiaohongshu", "toutiao", "51cto", "jianshu", "segmentfault",
    "oschina", "bilibili", "tencent_dev", "aliyun_dev",
  ];
  const allowedOrigins = new Set([
    "https://dinqor.cn",
    "https://xiaod.dpdns.org",
    "http://localhost:5173",
    "http://localhost:3000",
  ]);

  function safeDiagnostic(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
    const diagnostic = {};
    if (typeof value.sitePermissionGranted === "boolean") diagnostic.sitePermissionGranted = value.sitePermissionGranted;
    if (typeof value.sessionCookieFound === "boolean") diagnostic.sessionCookieFound = value.sessionCookieFound;
    if (Number.isInteger(value.cookieCount) && value.cookieCount >= 0 && value.cookieCount <= 1000) {
      diagnostic.cookieCount = value.cookieCount;
    }
    if (value.cookieScopes && typeof value.cookieScopes === "object" && !Array.isArray(value.cookieScopes)) {
      const scopes = {};
      for (const key of ["domain", "userToken", "username", "partitioned", "partitionedToken", "identityHints"]) {
        const count = value.cookieScopes[key];
        if (Number.isInteger(count) && count >= 0 && count <= 1000) scopes[key] = count;
      }
      for (const key of ["cookieStoreMatched", "partitionKeyAvailable"]) {
        if (typeof value.cookieScopes[key] === "boolean") scopes[key] = value.cookieScopes[key];
      }
      if (Object.keys(scopes).length) diagnostic.cookieScopes = scopes;
    }
    if (Number.isInteger(value.httpStatus) && value.httpStatus >= 100 && value.httpStatus <= 599) {
      diagnostic.httpStatus = value.httpStatus;
    }
    if (Number.isInteger(value.responseCode) && value.responseCode >= 100 && value.responseCode <= 599) {
      diagnostic.responseCode = value.responseCode;
    }
    return Object.keys(diagnostic).length ? diagnostic : undefined;
  }

  function sendRuntimeMessage(message, onReply) {
    let settled = false;
    const finish = (reply, failed) => {
      if (settled) return;
      settled = true;
      onReply?.(reply, failed);
    };

    try {
      if (!chrome.runtime?.id) {
        finish(null, true);
        return;
      }
      chrome.runtime.sendMessage(message, (reply) => {
        try {
          finish(reply, Boolean(chrome.runtime.lastError));
        } catch {
          finish(null, true);
        }
      });
    } catch {
      finish(null, true);
    }
  }

  chrome.runtime.onMessage.addListener((message) => {
    if (message?.type !== "AUTHORIZATION_PROGRESS" || !taskIdPattern.test(message.taskId)
      || !["WAITING_FOR_SESSION", "SUBMITTING", "RETRYING", "COMPLETED", "FAILED"].includes(message.state)) return false;

    const progress = {
      channel,
      type: "AUTHORIZATION_PROGRESS",
      taskId: message.taskId,
      state: message.state,
      errorCode: typeof message.errorCode === "string" ? message.errorCode : null,
    };
    const diagnostic = safeDiagnostic(message.diagnostic);
    if (diagnostic) progress.diagnostic = diagnostic;
    window.postMessage(progress, window.location.origin);
    return false;
  });

  window.addEventListener("message", (event) => {
    if (event.source !== window || event.origin !== window.location.origin
      || !allowedOrigins.has(event.origin) || event.data?.channel !== channel) return;

    const message = event.data;
    if (message.type === "PING") {
      sendRuntimeMessage({ type: "PING" }, (reply, failed) => {
        window.postMessage({ channel, type: "PONG", requestId: message.requestId, available: !failed && Boolean(reply?.available) }, event.origin);
      });
      return;
    }
    if (message.type === "CANCEL" && typeof message.taskId === "string") {
      sendRuntimeMessage({ type: "CANCEL", taskId: message.taskId });
      return;
    }
    if (message.type === "READ_PROFILE" && typeof message.requestId === "string") {
      sendRuntimeMessage({ type: "READ_WECHAT_PROFILE" }, (reply, failed) => {
        const profile = reply?.profile;
        const ok = !failed && reply?.ok === true && typeof profile?.nickname === "string";
        window.postMessage({
          channel,
          type: "PROFILE_RESULT",
          requestId: message.requestId,
          ok,
          errorCode: ok ? null : (reply?.errorCode || "WECHAT_PROFILE_NOT_FOUND"),
          profile: ok ? {
            nickname: profile.nickname.slice(0, 200),
            avatarUrl: typeof profile.avatarUrl === "string" ? profile.avatarUrl : null,
          } : null,
        }, event.origin);
      });
      return;
    }
    if (message.type !== "START" || typeof message.requestId !== "string"
      || !supportedPlatforms.includes(message.platform)
      || typeof message.taskId !== "string" || typeof message.transferToken !== "string") return;

    sendRuntimeMessage({
      type: "START",
      platform: message.platform,
      taskId: message.taskId,
      transferToken: message.transferToken,
    }, (reply, failed) => {
      window.postMessage({
        channel,
        type: "START_RESULT",
        requestId: message.requestId,
        ok: !failed && Boolean(reply?.ok),
      }, event.origin);
    });
  });
})();
