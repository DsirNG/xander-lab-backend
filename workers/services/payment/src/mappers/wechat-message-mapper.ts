import type { WechatMessagePort } from "../interfaces/wechat-message-port.js";
import type { WechatDeliveryNotification } from "../interfaces/wechat-payment-port.js";

const encoder = new TextEncoder();
const FIELDS = ["Event", "OpenId", "OutTradeNo", "MchOrderNo", "WxOrderId", "ProductId", "Quantity", "Env"] as const;

function constantTimeEqual(left: string, right: string): boolean {
  const a = encoder.encode(left);
  const b = encoder.encode(right);
  let difference = a.length ^ b.length;
  const length = Math.max(a.length, b.length);
  for (let index = 0; index < length; index += 1) difference |= (a[index] ?? 0) ^ (b[index] ?? 0);
  return difference === 0;
}

function decodeXmlText(value: string): string {
  return value.replace(/&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, (entity, name: string) => {
    if (name === "amp") return "&";
    if (name === "lt") return "<";
    if (name === "gt") return ">";
    if (name === "quot") return '"';
    if (name === "apos") return "'";
    const codePoint = name.startsWith("#x") ? Number.parseInt(name.slice(2), 16) : Number.parseInt(name.slice(1), 10);
    if (!Number.isInteger(codePoint) || codePoint < 1 || codePoint > 0x10ffff
      || (codePoint >= 0xd800 && codePoint <= 0xdfff)) throw new Error("invalid XML character reference");
    return String.fromCodePoint(codePoint);
  }).replace(/&[^;\s]+;/g, () => { throw new Error("unsupported XML entity"); });
}

function getTag(xml: string, tag: string): string | null {
  const matches = [...xml.matchAll(new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`, "g"))];
  if (matches.length > 1) throw new Error("duplicate XML field");
  if (matches.length === 0) return null;
  const raw = matches[0][1];
  const cdata = raw.match(/^<!\[CDATA\[([\s\S]*)\]\]>$/);
  if (cdata) return cdata[1].trim();
  if (/[<>]/.test(raw)) throw new Error("nested XML field");
  return decodeXmlText(raw).trim();
}

export class WechatMessageMapper implements WechatMessagePort {
  async verifySignature(signature: string | null, timestamp: string | null, nonce: string | null, token: string | undefined): Promise<boolean> {
    if (!signature || !/^[\da-fA-F]{40}$/.test(signature) || !token || !timestamp || !nonce) return false;
    const actual = [token, timestamp, nonce].sort().join("");
    const digest = await crypto.subtle.digest("SHA-1", encoder.encode(actual));
    const hex = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
    return constantTimeEqual(hex, signature.toLowerCase());
  }

  parseDeliveryNotification(xml: string): WechatDeliveryNotification {
    try {
      if (encoder.encode(xml).byteLength > 64 * 1024 || /<!\s*(?:DOCTYPE|ENTITY)/i.test(xml)) throw new Error("unsafe XML");
      const normalized = xml.trim().replace(/^<\?xml\s+[^?]*\?>\s*/i, "");
      if (!/^<xml(?:\s[^>]*)?>[\s\S]*<\/xml>$/.test(normalized)) throw new Error("invalid root");
      const rootContent = normalized.replace(/^<xml(?:\s[^>]*)?>/, "").replace(/<\/xml>$/, "");
      if (/<\?(?!xml\s)|<!--/.test(rootContent)) throw new Error("unsupported XML markup");
      const residue = rootContent.replace(/<([A-Za-z][A-Za-z0-9]*)>([\s\S]*?)<\/\1>/g, (_match, tag: string) => {
        if (!(FIELDS as readonly string[]).includes(tag)) throw new Error("unknown XML field");
        return "";
      });
      if (residue.trim()) throw new Error("malformed XML field");
      for (const tag of FIELDS) {
        const count = [...rootContent.matchAll(new RegExp(`<${tag}>`, "g"))].length;
        if (count > 1) throw new Error("duplicate XML field");
      }
      const quantityText = getTag(rootContent, "Quantity");
      const envText = getTag(rootContent, "Env");
      const quantity = quantityText === null ? 1 : Number(quantityText);
      const env = envText === null ? -1 : Number(envText);
      if (!Number.isSafeInteger(quantity) || !Number.isSafeInteger(env)) throw new Error("invalid number");
      const wxOrderId = getTag(rootContent, "MchOrderNo") || getTag(rootContent, "WxOrderId");
      return {
        event: getTag(rootContent, "Event") ?? "",
        openid: getTag(rootContent, "OpenId"),
        outTradeNo: getTag(rootContent, "OutTradeNo"),
        wxOrderId,
        productId: getTag(rootContent, "ProductId"),
        quantity,
        env,
      };
    } catch {
      throw new Error("微信发货通知格式无效");
    }
  }
}
