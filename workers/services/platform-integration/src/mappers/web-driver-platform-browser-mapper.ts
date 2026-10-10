import { PlatformAuthorizationError, type PlatformBrowserPort } from "../interfaces/platform-authorization.js";
import type { PublishingPlatform } from "../interfaces/platform-credential-repository.js";

const ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf";
const MAX_RESPONSE_BYTES = 512 * 1024;
const CSDN_URL = "https://mp.csdn.net/";
const JUEJIN_URL = "https://juejin.cn/editor/drafts/new?v=2";

type JsonObject = Record<string, unknown>;

/** W3C WebDriver adapter. Browser sessions live outside Workers; only their encrypted IDs enter D1. */
export class WebDriverPlatformBrowserMapper implements PlatformBrowserPort {
  private readonly baseUrl: URL | null;

  constructor(endpoint: string | undefined, private readonly fetcher: typeof fetch = fetch) {
    this.baseUrl = endpoint ? this.parseEndpoint(endpoint) : null;
  }

  async start(platform: PublishingPlatform): Promise<{ sessionId: string; qrCodeDataUrl: string | null }> {
    if (!this.baseUrl) throw new PlatformAuthorizationError(503, "平台扫码授权尚未配置浏览器服务");
    const created = await this.command("/session", "POST", {
      capabilities: {
        alwaysMatch: {
          browserName: "chrome",
          "goog:chromeOptions": { args: ["--headless=new", "--no-sandbox", "--disable-dev-shm-usage"] },
        },
      },
    });
    const root = asObject(created);
    const value = asObject(root.value);
    const sessionId = value.sessionId ?? root.sessionId;
    if (typeof sessionId !== "string" || !/^[A-Za-z0-9._-]{1,128}$/.test(sessionId)) {
      throw new PlatformAuthorizationError(503, "浏览器授权服务返回了无效会话");
    }
    try {
      await this.command(`/session/${encodeURIComponent(sessionId)}/url`, "POST", {
        url: platform === "csdn" ? CSDN_URL : JUEJIN_URL,
      });
      return { sessionId, qrCodeDataUrl: await this.qrCode(platform, sessionId) };
    } catch (error) {
      await this.close(sessionId);
      throw error;
    }
  }

  async qrCode(platform: PublishingPlatform, sessionId: string): Promise<string | null> {
    this.validateSessionId(sessionId);
    let element: string | null = null;
    if (platform === "csdn") {
      await this.command(`/session/${sessionId}/frame`, "POST", { id: null });
      const frame = await this.findElement(sessionId, "css selector", "iframe[src*='passport.csdn.net']");
      if (!frame) return null;
      await this.command(`/session/${sessionId}/frame`, "POST", { id: { [ELEMENT_KEY]: frame } });
      element = await this.findElement(sessionId, "css selector", ".public-code img[src^='data:image/']");
      try { await this.command(`/session/${sessionId}/frame`, "POST", { id: null }); } catch { /* frame cleanup is best effort */ }
    } else {
      element = await this.findElement(sessionId, "css selector", ".qrcode-img-wrap img.qrcode-img");
    }
    if (!element) return null;
    const result = asObject(await this.command(`/session/${sessionId}/element/${element}/attribute/src`, "GET"));
    const value = result.value;
    return typeof value === "string" && /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(value)
      && value.length <= 500_000 ? value : null;
  }

  async cookies(sessionId: string): Promise<Array<{ name: string; value: string }>> {
    this.validateSessionId(sessionId);
    const result = asObject(await this.command(`/session/${sessionId}/cookie`, "GET"));
    const value = result.value;
    const cookies = Array.isArray(value) ? value : asObject(value).cookies;
    if (!Array.isArray(cookies) || cookies.length > 100) {
      throw new PlatformAuthorizationError(503, "浏览器授权服务返回了无效 Cookie 状态");
    }
    return cookies.flatMap((cookie) => {
      const row = asObject(cookie);
      return typeof row.name === "string" && /^[A-Za-z0-9_.-]{1,200}$/.test(row.name)
        && typeof row.value === "string" && row.value.length <= 16_384
        ? [{ name: row.name, value: row.value }] : [];
    });
  }

  async close(sessionId: string): Promise<void> {
    this.validateSessionId(sessionId);
    try { await this.command(`/session/${sessionId}`, "DELETE"); } catch { /* lease release must survive a stale Grid session */ }
  }

  private async findElement(sessionId: string, using: string, value: string): Promise<string | null> {
    try {
      const result = asObject(await this.command(`/session/${sessionId}/element`, "POST", { using, value }));
      const element = asObject(result.value)[ELEMENT_KEY] ?? asObject(result.value).ELEMENT;
      return typeof element === "string" && /^[A-Za-z0-9._-]{1,128}$/.test(element) ? element : null;
    } catch {
      return null;
    }
  }

  private async command(path: string, method: string, body?: JsonObject): Promise<unknown> {
    if (!this.baseUrl) throw new PlatformAuthorizationError(503, "平台扫码授权尚未配置浏览器服务");
    const url = new URL(`${this.baseUrl.pathname.replace(/\/$/, "")}${path}`, this.baseUrl.origin);
    let response: Response;
    try {
      response = await this.fetcher(url, {
        method,
        redirect: "manual",
        signal: AbortSignal.timeout(5_000),
        ...(body ? { headers: { "content-type": "application/json" }, body: JSON.stringify(body) } : {}),
      });
    } catch {
      throw new PlatformAuthorizationError(503, "平台扫码授权服务暂时不可用");
    }
    if (!response.ok || response.status >= 300) {
      throw new PlatformAuthorizationError(503, "平台扫码授权服务暂时不可用");
    }
    const raw = await boundedText(response, MAX_RESPONSE_BYTES);
    let result: unknown;
    try { result = raw ? JSON.parse(raw) : {}; } catch {
      throw new PlatformAuthorizationError(503, "浏览器授权服务返回了无效响应");
    }
    const value = asObject(asObject(result).value);
    if (typeof value.error === "string") throw new PlatformAuthorizationError(503, "浏览器授权操作失败");
    return result;
  }

  private validateSessionId(sessionId: string): void {
    if (!/^[A-Za-z0-9._-]{1,128}$/.test(sessionId)) {
      throw new PlatformAuthorizationError(503, "平台授权会话参数无效");
    }
  }

  private parseEndpoint(endpoint: string): URL {
    let url: URL;
    try { url = new URL(endpoint); } catch {
      throw new PlatformAuthorizationError(503, "平台浏览器服务地址配置无效");
    }
    const localHosts = new Set(["localhost", "127.0.0.1", "[::1]", "selenium"]);
    if ((url.protocol !== "https:" && !(url.protocol === "http:" && localHosts.has(url.hostname)))
      || url.username || url.password || url.search || url.hash) {
      throw new PlatformAuthorizationError(503, "平台浏览器服务地址配置无效");
    }
    url.pathname = url.pathname.replace(/\/+$/, "");
    return url;
  }
}

function asObject(value: unknown): JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as JsonObject : {};
}

async function boundedText(response: Response, limit: number): Promise<string> {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > limit) {
        await reader.cancel();
        throw new PlatformAuthorizationError(503, "浏览器授权响应超过允许大小");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}
