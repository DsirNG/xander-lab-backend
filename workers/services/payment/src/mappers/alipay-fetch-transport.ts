/**
 * Wrangler's urllib alias: the official SDK retains signing/response verification,
 * while gateway HTTP uses Workers fetch instead of Node sockets/undici.
 * Only the SDK's V2 text/form request contract is supported.
 */
export class Agent {
  constructor(_options?: unknown) {}
}

export class ProxyAgent {
  constructor() { throw new Error("Payment transport does not support proxies"); }
}

interface Options {
  method: string;
  data: Record<string, string>;
  dataType: string;
  timeout: number;
  headers: Record<string, string>;
  dispatcher?: unknown;
}

export async function request(url: string, options: Options) {
  const endpoint = new URL(url);
  if (!["openapi.alipay.com", "openapi-sandbox.dl.alipaydev.com"].includes(endpoint.hostname)
    || endpoint.protocol !== "https:" || endpoint.pathname !== "/gateway.do"
    || endpoint.username || endpoint.password || endpoint.port
    || options.method !== "POST" || options.dataType !== "text" || options.dispatcher) {
    throw new Error("Unsupported payment transport request");
  }
  const response = await fetch(endpoint, {
    method: "POST", body: new URLSearchParams(options.data), redirect: "manual",
    headers: { ...options.headers, "content-type": "application/x-www-form-urlencoded;charset=UTF-8" },
    signal: AbortSignal.timeout(Math.min(options.timeout || 8000, 8000)),
  });
  if (response.status >= 300 && response.status < 400) throw new Error("Payment redirects are forbidden");
  if (Number(response.headers.get("content-length")) > 64 * 1024) throw new Error("Payment response too large");
  const reader = response.body?.getReader();
  if (!reader) throw new Error("Empty payment response");
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 64 * 1024) { await reader.cancel(); throw new Error("Payment response too large"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  const headers: Record<string,string> = {};
  response.headers.forEach((value,key) => { headers[key] = value; });
  return { status: response.status, headers,
    data: new TextDecoder("utf-8", { fatal: true }).decode(bytes) };
}

export default { request };
