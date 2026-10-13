(() => {
  const textFrom = (selector) => {
    for (const node of document.querySelectorAll(selector)) {
      const value = (node.getAttribute("title") || node.getAttribute("aria-label") || node.textContent || "").trim();
      if (value && value.length <= 200 && !["公众号", "公众号设置", "账号信息", "切换账号"].includes(value)) return value;
    }
    return null;
  };

  const readProfile = () => {
    const nickname = textFrom([
      "#js_account_name", "a.weui-desktop-account__nickname", ".weui-desktop-account__nickname",
      ".weui-desktop-account__name", "[class*='account'][class*='nickname']",
      "[class*='account'][class*='name']", "[class*='account'][class*='nick']",
    ].join(","));
    const images = document.querySelectorAll([
      "#js_account_avatar img", ".weui-desktop-account__avatar img", "img.weui-desktop-account__avatar",
      "[class*='account-avatar'] img", "img[class*='avatar']",
    ].join(","));
    let avatarUrl = null;
    for (const image of images) {
      try {
        const url = new URL(image.currentSrc || image.src || image.getAttribute("data-src") || "", location.href);
        if (url.protocol === "https:" && ["wx.qlogo.cn", "thirdwx.qlogo.cn", "mmbiz.qpic.cn"].includes(url.hostname)) {
          avatarUrl = url.href;
          break;
        }
      } catch { /* Ignore optional or malformed profile images. */ }
    }
    return nickname ? { nickname, avatarUrl } : null;
  };

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type !== "READ_WECHAT_ACCOUNT_PROFILE") return false;
    const readWhenVisible = async () => {
      const deadline = Date.now() + 8000;
      let profile = readProfile();
      while (!profile && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 250));
        profile = readProfile();
      }
      sendResponse(profile);
    };
    void readWhenVisible();
    return true;
  });
})();
