# DinQorAI browser extension

This Manifest V3 extension lets a signed-in DinQorAI user connect publishing accounts using the browser they already use. It opens the selected platform in a normal tab, waits for a recognized signed-in session cookie, then sends the credential to the short-lived, one-use Workers authorization task. It does not copy cookies from the browser into the website page.

The connector recognizes CSDN, Juejin, WeChat Official Account, Weibo, Zhihu, Baijiahao, Cnblogs, Douyin, Xiaohongshu, Toutiao, 51CTO, Jianshu, SegmentFault, OSChina, Bilibili, Tencent Cloud Developer Community, and Alibaba Cloud Developer Community. CSDN and Juejin retain one-click publishing support. The other platforms are account-binding only; publishing adapters are not included. During WeChat Official Account binding, the extension also reads the visible account name and avatar from the logged-in console page; profile fields are optional and are stored separately from the encrypted session cookie.

## Load for local development

1. Open `chrome://extensions` or `edge://extensions`.
2. Turn on Developer mode and choose **Load unpacked**.
3. Select this `browser-extension` folder.
4. Open DinQorAI, go to Profile settings → Publishing platforms, and choose Connect for a platform.
5. Scan or complete login in the platform tab opened by the extension. After binding succeeds, the extension closes the platform tab and activates the originating DinQorAI tab and window. If that tab has been closed, binding still completes without reopening it.

When upgrading to 0.3.2 or later, reload the unpacked extension and accept any added HTTP site access before starting a new authorization task. Chromium checks non-Secure cookies against HTTP host permissions even when a platform page uses HTTPS; HTTPS-only permissions can silently hide login cookies. This applies to CSDN and WeChat Official Account. The extension still opens platform pages and submits credentials over HTTPS. Page load/refresh completion triggers an immediate session recheck in addition to cookie-change events and the periodic alarm.

The authorization dialog distinguishes a missing session cookie from a failed server submission, shows safe CSDN cookie-scope counts when available, and shows the completion HTTP status/code. For local diagnostics, open `chrome://extensions`, select the connector's **service worker** inspector, and filter the console for `[DinQor Connector] authorization`. Logs contain platform, phase, cookie counts, and result/status only; cookie values, account names, and handoff tokens are never logged.

The frontend's current Vite port is `http://localhost:3000`. For a local frontend using the production backend, no API edits are needed: Vite proxies `/api` to `https://api.xiaodongding.dpdns.org` by default, and this extension's `config.js` uses the same Gateway. Sign into the production DinQorAI account in the local frontend.

For a fully isolated local test, start the Workers stack from the `workers` directory with `pnpm dev`; it applies local D1 migrations and starts the Gateway at `http://127.0.0.1:8787`. If creating a local email/password account, set a local-only `AUTH_PASSWORD_PEPPER` of at least 32 characters in that terminal before starting the stack. Set `VITE_API_BASE_URL=http://127.0.0.1:8787` in the frontend's `.env.local`, restart Vite, and sign into a local frontend account. Change `API_BASE_URL` in this folder's `config.js` to `http://127.0.0.1:8787`, then reload the extension from `chrome://extensions` or `edge://extensions`. On the extension Details page, grant site access to `http://localhost:3000` and `http://127.0.0.1:8787`. The credential then stays in the local Workers D1 database.

For local frontend development, add any new frontend origin to `content-bridge.js`, `config.js`, and the manifest content-script matches. Keep API host permissions limited to the deployed Gateway and explicit local development hosts.

## Permissions and distribution

The extension requests cookie access only for DinQorAI and the listed platform sites, plus tab/storage/alarm access to finish a short-lived authorization task when its service worker sleeps. The task grant expires after five minutes and is stored server-side only as a SHA-256 hash. Platform credentials are encrypted by Workers before persistence. Disconnecting removes the saved credential from DinQor but does not sign the user out of the platform.

The folder can be loaded unpacked for development. Public installation requires packaging and review through the Chrome Web Store and, separately, Microsoft Edge Add-ons. Store submission needs a privacy policy explaining cookie access, credential transmission, retention, and deletion.
