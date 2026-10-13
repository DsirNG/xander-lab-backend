import { Marked, Renderer, type Tokens } from "marked";
import type { CsdnArticleInput, CsdnPublicationPort, CsdnPublicationResult } from "../interfaces/csdn-publication-port.js";
import { CsdnProviderError } from "../interfaces/csdn-publication-port.js";
import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import type { PlatformAccountPort, PlatformAccountProfile } from "../interfaces/platform-account-port.js";

const API_ORIGIN = "https://bizapi.csdn.net";
const SAVE_PATH = "/blog-console-api/v1/postedit/saveArticle";
const IMAGE_PATH = "/resource-api/v1/image/external/storage";
const ACCEPT = "application/json, text/plain, */*";
const SAVE_CONTENT_TYPE = "application/json;";
const IMAGE_CONTENT_TYPE = "application/json;charset=UTF-8";
const MAX_RESPONSE_BYTES = 1024 * 1024;
const MAX_IMAGES = 30;

interface CsdnCredential {
  cookie?: string;
}

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

function escapeHtml(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function safeRemoteUrl(value: string, allowMailto = false): string | null {
  try {
    const url = new URL(value);
    if (url.username || url.password || url.protocol !== "https:" && url.protocol !== "http:"
      && !(allowMailto && url.protocol === "mailto:")) return null;
    if (url.protocol === "mailto:") return url.href;
    const host = url.hostname.toLowerCase();
    const ipv6 = host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : "";
    if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")
      || host.endsWith(".internal")
      || (ipv6 && (ipv6 === "::" || ipv6 === "::1" || /^f[cd]/.test(ipv6)
        || /^fe[89ab]/.test(ipv6) || ipv6.startsWith("::ffff:")))) return null;
    const octets = host.split(".").map(Number);
    if (octets.length === 4 && octets.every((part) => Number.isInteger(part) && part >= 0 && part <= 255)) {
      const [a, b] = octets;
      if (a === 0 || a === 10 || a === 127 || a >= 224 || a === 169 && b === 254
        || a === 172 && b >= 16 && b <= 31 || a === 192 && b === 168) return null;
    }
    return url.href;
  } catch {
    return null;
  }
}

async function readResponseText(response: Response): Promise<string> {
  const contentLength = response.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_RESPONSE_BYTES) {
    throw new CsdnProviderError(false, "CSDN 接口响应过大");
  }
  if (!response.body) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new CsdnProviderError(false, "CSDN 接口响应过大");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new CsdnProviderError(false, "CSDN 接口响应格式无效");
  }
}

function parseProviderJson(raw: string): Record<string, unknown> {
  try {
    const lossless = raw.replace(/("(?:article_id|id)"\s*:\s*)(\d{16,})(?=\s*[,}])/g, "$1\"$2\"");
    const value = record(JSON.parse(lossless));
    if (!value) throw new Error("invalid response");
    return value;
  } catch {
    throw new CsdnProviderError(false, "CSDN 接口响应格式无效");
  }
}

function providerFailure(response: Response, root: Record<string, unknown>): CsdnProviderError {
  const code = Number(root.code ?? 0);
  const expired = response.status === 401 || response.status === 403 || code === 401;
  return new CsdnProviderError(expired, expired ? "CSDN 授权已失效" : "CSDN 接口暂不可用");
}

function providerCode(root: Record<string, unknown>): number {
  const code = Number(root.code ?? 0);
  return Number.isFinite(code) ? code : 0;
}

function articleId(value: unknown): string {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (typeof value === "number" && Number.isSafeInteger(value) && value > 0) return String(value);
  return "";
}

function normalizeDescription(summary: string): string {
  return summary.trim().slice(0, 200);
}

export class CsdnPublicationMapper implements CsdnPublicationPort, PlatformAccountPort {
  constructor(private readonly env: PlatformEnvironment) {}

  async getCurrentAccount(credential: Record<string, string>): Promise<PlatformAccountProfile> {
    const cookie = credential.cookie;
    if (!cookie || /[\r\n]/.test(cookie)) throw new CsdnProviderError(true, "CSDN 授权已失效");
    // Account reads use the current mp console's signing pair, independently of publishing.
    const apiKey = (this.env.PLATFORM_CSDN_ACCOUNT_API_KEY || this.env.PLATFORM_CSDN_API_KEY)?.trim();
    const secret = this.env.PLATFORM_CSDN_ACCOUNT_API_SECRET || this.env.PLATFORM_CSDN_WEB_CONSOLE_SECRET;
    if (!apiKey || !secret) throw new CsdnProviderError(false, "CSDN 接口签名尚未配置");
    const path = "/blog-console-api/v1/user/info";
    const nonce = crypto.randomUUID();
    // No muser parameter: CSDN must resolve the current account from the saved session.
    const canonical = `GET\n${ACCEPT}\n\n\n\nx-ca-key:${apiKey}\nx-ca-nonce:${nonce}\n${path}`;
    let response: Response;
    let root: Record<string, unknown>;
    try {
      response = await fetch(new URL(path, API_ORIGIN), {
        method: "GET",
        headers: {
          accept: ACCEPT, cookie, origin: "https://mp.csdn.net", referer: "https://mp.csdn.net/",
          "x-ca-key": apiKey, "x-ca-nonce": nonce,
          "x-ca-signature-headers": "x-ca-key,x-ca-nonce",
          "x-ca-stage": "",
          "x-ca-signature": await this.sign(secret, canonical),
        },
        redirect: "manual", signal: AbortSignal.timeout(15_000),
      });
      // An HTML login redirect or authentication challenge must not pass as a profile.
      if (response.status >= 300 && response.status < 400
        && response.headers.get("location")?.startsWith("https://passport.csdn.net/")) {
        await response.body?.cancel();
        throw new CsdnProviderError(true, "CSDN 授权已失效");
      }
      root = parseProviderJson(await readResponseText(response));
    } catch (error) {
      if (error instanceof CsdnProviderError) throw error;
      throw new CsdnProviderError(false, "CSDN 接口暂不可用");
    }
    // A forbidden response can also mean a signing error or provider blocking, not expiry.
    if (Number(root.code) === 401) throw new CsdnProviderError(true, "CSDN 授权已失效");
    if (!response.ok || Number(root.code) !== 200) throw new CsdnProviderError(false, "CSDN 接口暂不可用");
    const data = record(root.data);
    const username = typeof data?.username === "string" ? data.username.trim() : "";
    if (!username || username.length > 200) throw new CsdnProviderError(false, "CSDN 账号信息格式无效");
    return {
      username,
      nickname: typeof data?.nickname === "string" ? data.nickname.slice(0, 200) : null,
      avatarUrl: typeof data?.avatar === "string" && data.avatar.startsWith("https://")
        ? safeRemoteUrl(data.avatar) : null,
    };
  }

  async publish(credential: Record<string, string>, input: CsdnArticleInput): Promise<CsdnPublicationResult> {
    const cookie = (credential as CsdnCredential).cookie;
    if (!cookie || /[\r\n]/.test(cookie)) throw new CsdnProviderError(true, "CSDN 授权已失效");
    const apiKey = this.env.PLATFORM_CSDN_API_KEY?.trim();
    const webConsoleSecret = this.env.PLATFORM_CSDN_WEB_CONSOLE_SECRET;
    if (!apiKey || !webConsoleSecret) throw new CsdnProviderError(false, "CSDN 发布凭证尚未配置");

    const imageSources: string[] = [];
    let parser: Marked;
    const renderer = new Renderer();
    renderer.html = (token: Tokens.HTML | Tokens.Tag) => escapeHtml(token.text);
    renderer.link = function (this: Renderer, token: Tokens.Link) {
      const body = this.parser.parseInline(token.tokens);
      const href = safeRemoteUrl(token.href, true);
      if (!href) return body;
      const title = token.title ? ` title="${escapeHtml(token.title)}"` : "";
      return `<a href="${escapeHtml(href)}"${title}>${body}</a>`;
    };
    renderer.image = (token: Tokens.Image) => {
      const source = safeRemoteUrl(token.href);
      if (!source) return escapeHtml(token.text);
      if (imageSources.length >= MAX_IMAGES) throw new CsdnProviderError(false, "文章图片数量超过限制");
      imageSources.push(source);
      const title = token.title ? ` title="${escapeHtml(token.title)}"` : "";
      return `<img src="${escapeHtml(source)}" alt="${escapeHtml(token.text)}"${title}>`;
    };
    parser = new Marked({ gfm: true, renderer });
    const converted = parser.parse(input.content);
    if (typeof converted !== "string") throw new CsdnProviderError(false, "文章格式无法转换");

    const originalUrl = `https://dinqor.cn/blog/${input.postId}`;
    let html = `<p><a href="${originalUrl}" target="_blank" rel="noopener noreferrer">原文链接</a></p>${converted}`;
    const hostedImages: string[] = [];
    for (const source of imageSources) {
      const hosted = await this.uploadImage(cookie, apiKey, webConsoleSecret, String(-input.postId), source);
      const originalAttribute = `src="${escapeHtml(source)}"`;
      const hostedAttribute = `src="${escapeHtml(hosted)}"`;
      html = html.replace(originalAttribute, hostedAttribute);
      hostedImages.push(hosted);
    }

    const coverCandidate = input.coverImage?.trim();
    const coverUrl = coverCandidate ? safeRemoteUrl(coverCandidate) : hostedImages[0] ?? null;
    const coverImages = coverUrl ? [coverUrl] : [];
    const root = await this.saveArticle(cookie, apiKey, webConsoleSecret, {
      title: input.title.trim(),
      description: normalizeDescription(input.summary),
      content: html,
      tags: input.tags,
      category: input.category ?? "",
      coverImages,
    });

    const data = root.data;
    if (typeof data === "string" && data === "成功") {
      return { externalId: `accepted:${input.postId}`, url: originalUrl };
    }
    const payload = record(data);
    const id = articleId(payload?.article_id ?? payload?.id);
    const url = typeof payload?.url === "string" ? payload.url.trim() : "";
    if (!id || !url || !safeRemoteUrl(url)) throw new CsdnProviderError(false, "CSDN 发布响应缺少文章信息");
    return { externalId: id, url };
  }

  private async saveArticle(
    cookie: string,
    apiKey: string,
    secret: string,
    article: { title: string; description: string; content: string; tags: string[]; category: string; coverImages: string[] },
  ): Promise<Record<string, unknown>> {
    const nonce = crypto.randomUUID();
    const body = {
      article_id: "",
      title: article.title,
      description: article.description,
      content: article.content,
      tags: article.tags.join(","),
      categories: article.category,
      type: "original",
      status: 0,
      read_type: "public",
      authorized_status: true,
      original_link: "",
      source: "pc_mdeditor",
      not_auto_saved: 0,
      cover_images: article.coverImages,
      cover_type: 0,
      vote_id: 0,
      resource_id: "",
      is_new: 1,
      sync_git_code: 0,
    };
    const canonical = `POST\n${ACCEPT}\n\n${SAVE_CONTENT_TYPE}\n\nx-ca-key:${apiKey}\nx-ca-nonce:${nonce}\n${SAVE_PATH}`;
    return this.post(SAVE_PATH, body, {
      accept: ACCEPT,
      "content-type": SAVE_CONTENT_TYPE,
      "x-ca-key": apiKey,
      "x-ca-nonce": nonce,
      "x-ca-signature-headers": "x-ca-key,x-ca-nonce",
      "x-ca-signature": await this.sign(secret, canonical),
      cookie,
    });
  }

  private async uploadImage(
    cookie: string,
    apiKey: string,
    secret: string,
    articleIdValue: string,
    imageUrl: string,
  ): Promise<string> {
    const nonce = crypto.randomUUID();
    const timestamp = String(Date.now());
    const body = {
      art_id: articleIdValue,
      imgUrl: imageUrl,
      type: "blog",
      rtype: "article",
      isCrawler: 0,
      nocache: 2,
    };
    const canonical = `POST\n${ACCEPT}\n\n${IMAGE_CONTENT_TYPE}\n\nx-ca-key:${apiKey}\nx-ca-nonce:${nonce}\nx-ca-timestamp:${timestamp}\n${IMAGE_PATH}`;
    const root = await this.post(IMAGE_PATH, body, {
      accept: ACCEPT,
      "content-type": IMAGE_CONTENT_TYPE,
      origin: "https://mp.csdn.net",
      referer: "https://mp.csdn.net/",
      "x-ca-key": apiKey,
      "x-ca-nonce": nonce,
      "x-ca-timestamp": timestamp,
      "x-ca-signature-headers": "x-ca-key,x-ca-nonce,x-ca-timestamp",
      "x-ca-signature": await this.sign(secret, canonical),
      cookie,
    });
    const data = root.data;
    if (typeof data === "string") {
      const url = safeRemoteUrl(data);
      if (url) return url;
    }
    const value = record(data);
    for (const key of ["url", "imgUrl", "imageUrl", "image_url", "src", "link"]) {
      const candidate = value?.[key];
      if (typeof candidate === "string") {
        const url = safeRemoteUrl(candidate);
        if (url) return url;
      }
    }
    throw new CsdnProviderError(false, "CSDN 图片上传返回了无效地址");
  }

  private async post(path: string, body: unknown, headers: Record<string, string>): Promise<Record<string, unknown>> {
    let response: Response;
    let root: Record<string, unknown>;
    try {
      response = await fetch(new URL(path, API_ORIGIN), {
        method: "POST",
        headers,
        body: JSON.stringify(body),
        redirect: "manual",
        signal: AbortSignal.timeout(15_000),
      });
      const raw = await readResponseText(response);
      root = parseProviderJson(raw);
    } catch (error) {
      if (error instanceof CsdnProviderError) throw error;
      throw new CsdnProviderError(false, "CSDN 接口暂不可用");
    }
    if (!response.ok || providerCode(root) !== 200) throw providerFailure(response, root);
    return root;
  }

  private async sign(secret: string, canonical: string): Promise<string> {
    try {
      const key = await crypto.subtle.importKey(
        "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
      );
      const signature = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(canonical)));
      let binary = "";
      for (const byte of signature) binary += String.fromCharCode(byte);
      return btoa(binary);
    } catch {
      throw new CsdnProviderError(false, "CSDN 请求签名失败");
    }
  }
}
