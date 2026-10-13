(() => {
  const textFrom = (selector) => {
    for (const node of document.querySelectorAll(selector)) {
      if (!node.getClientRects().length) continue;
      const value = (node.getAttribute("title") || node.getAttribute("aria-label") || node.textContent || "").trim();
      if (value && value.length <= 200) return value;
    }
    return null;
  };

  const readProfile = () => {
    const nickname = textFrom([
      "#js_account_name", ".weui-desktop-account__nickname", ".weui-desktop-account__name",
      "[class*='account'][class*='nickname']", "[class*='account'][class*='name']",
    ].join(","));
    const image = document.querySelector([
      "#js_account_avatar img", ".weui-desktop-account__avatar img", "img.weui-desktop-account__avatar",
      "[class*='account-avatar'] img", "img[class*='avatar']",
    ].join(","));
    let avatarUrl = null;
    if (image?.getClientRects().length) {
      try {
        const url = new URL(image.currentSrc || image.src, location.href);
        if (url.protocol === "https:" && ["wx.qlogo.cn", "thirdwx.qlogo.cn", "mmbiz.qpic.cn"].includes(url.hostname)) {
          avatarUrl = url.href;
        }
      } catch { /* Ignore optional or malformed profile images. */ }
    }
    return nickname ? { nickname, avatarUrl } : null;
  };

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type !== "READ_WECHAT_ACCOUNT_PROFILE") return false;
    const readWhenVisible = async () => {
      const deadline = Date.now() + 5000;
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
