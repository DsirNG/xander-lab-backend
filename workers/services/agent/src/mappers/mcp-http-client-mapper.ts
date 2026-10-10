import type { AgentEnvironment } from "../interfaces/agent-environment.js";
import type { AgentMcpConnection } from "../interfaces/agent-mcp-connection.js";
import type { AgentMcpRemoteTool, AgentMcpServerRecord } from "../interfaces/agent-mcp-repository.js";

const MAX_MCP_RESPONSE_BYTES = 1_000_000;
const PROTOCOL_VERSION = "2025-03-26";

function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function isPrivateIpv4(value: string): boolean {
  const parts = value.split(".");
  if (parts.length !== 4 || parts.some((part) => !/^\d{1,3}$/.test(part) || Number(part) > 255)) return true;
  const [a, b] = parts.map(Number);
  return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254)
    || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
    || (a === 100 && b >= 64 && b <= 127) || (a === 198 && (b === 18 || b === 19)) || a >= 224;
}

async function assertPublicEndpoint(raw: string): Promise<URL> {
  let endpoint: URL;
  try { endpoint = new URL(raw); } catch { throw new Error("MCP 端点地址不是有效 URL"); }
  const host = endpoint.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.hash) {
    throw new Error("MCP 端点必须是无内嵌凭据的 HTTPS URL");
  }
  if (!host || host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")
    || host === "metadata.google.internal" || host.includes("%")) {
    throw new Error("MCP 端点主机名无效或指向本地地址");
  }
  if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) {
    if (isPrivateIpv4(host)) throw new Error("MCP 端点不能指向非公网 IP");
    return endpoint;
  }
  if (host.includes(":")) throw new Error("MCP 端点暂不接受 IPv6 字面地址");

  // Resolve before every outbound request and reject any private answer. Fetch redirects are
  // disabled below so a public endpoint cannot redirect the Worker to an internal address.
  let foundPublicAddress = false;
  for (const type of ["A", "AAAA"]) {
    const lookup = new URL("https://cloudflare-dns.com/dns-query");
    lookup.searchParams.set("name", host);
    lookup.searchParams.set("type", type);
    const response = await fetch(lookup, {
      headers: { accept: "application/dns-json" },
      redirect: "manual",
      signal: AbortSignal.timeout(3_000),
    });
    if (!response.ok) throw new Error("无法验证 MCP 端点是否为公网地址");
    const result: unknown = await response.json();
    if (!record(result) || !Array.isArray(result.Answer)) continue;
    for (const answer of result.Answer) {
      if (!record(answer) || typeof answer.data !== "string" || typeof answer.type !== "number") continue;
      const address = answer.data.toLowerCase();
      if (type === "A" && answer.type === 1) {
        if (isPrivateIpv4(address)) throw new Error("MCP 端点解析到非公网地址，已拒绝");
        foundPublicAddress = true;
      }
      if (type === "AAAA" && answer.type === 28) {
        const firstHextet = Number.parseInt(address.split(":")[0] || "0", 16);
        if (address === "::1" || address === "::" || firstHextet < 0x2000 || firstHextet > 0x3fff
          || address.startsWith("2001:db8:") || address.startsWith("2002:") || address.startsWith("3fff:")) {
          throw new Error("MCP 端点解析到非公网地址，已拒绝");
        }
        foundPublicAddress = true;
      }
    }
  }
  if (!foundPublicAddress) throw new Error("MCP 端点没有可用的公网 DNS 地址");
  return endpoint;
}

async function readBody(response: Response): Promise<unknown> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error("MCP 服务返回了空响应");
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_MCP_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("MCP 服务响应超过大小上限");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  if (response.headers.get("content-type")?.toLowerCase().includes("text/event-stream")) {
    const messages: unknown[] = [];
    for (const block of text.split(/\r?\n\r?\n/)) {
      const data = block.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trim()).join("\n");
      if (data && data !== "[DONE]") {
        try { messages.push(JSON.parse(data) as unknown); } catch { /* ignore non-JSON SSE events */ }
      }
    }
    return messages.find((message) => record(message) && ("result" in message || "error" in message)) ?? null;
  }
  if (!text) return null;
  try { return JSON.parse(text) as unknown; } catch { throw new Error("MCP 服务响应格式不正确"); }
}

/** Streamable HTTP MCP transport with strict bounds, no redirects, and no D1/network coupling. */
export class McpHttpClientMapper implements AgentMcpConnection {
  constructor(private readonly env: AgentEnvironment) {}

  async listTools(server: AgentMcpServerRecord, headers: Record<string, string>): Promise<AgentMcpRemoteTool[]> {
    const client = await this.initialize(server, headers);
    const result = await this.rpc(server, headers, client.sessionId, client.version, 2, "tools/list", {});
    const tools = record(result) && Array.isArray(result.tools) ? result.tools : [];
    return tools.filter((tool) => record(tool) && typeof tool.name === "string" && tool.name.length > 0)
      .slice(0, 50).map((tool) => ({
        name: String((tool as Record<string, unknown>).name).slice(0, 128),
        description: typeof (tool as Record<string, unknown>).description === "string"
          ? String((tool as Record<string, unknown>).description).slice(0, 2000) : "",
        inputSchema: record((tool as Record<string, unknown>).inputSchema)
          ? (tool as Record<string, unknown>).inputSchema as Record<string, unknown> : { type: "object", properties: {} },
      }));
  }

  async callTool(
    server: AgentMcpServerRecord,
    headers: Record<string, string>,
    toolName: string,
    argumentsValue: unknown,
  ): Promise<unknown> {
    const client = await this.initialize(server, headers);
    const result = await this.rpc(server, headers, client.sessionId, client.version, 2, "tools/call", {
      name: toolName,
      arguments: record(argumentsValue) ? argumentsValue : {},
    });
    const normalized = record(result) ? result : { isError: true, text: "远端 MCP 工具没有返回结果" };
    if (typeof normalized.isError === "boolean" && normalized.isError) {
      throw new Error("远端 MCP 工具执行失败");
    }
    const texts: string[] = [];
    if (Array.isArray(normalized.content)) {
      for (const block of normalized.content) {
        if (record(block) && block.type === "text" && typeof block.text === "string") texts.push(block.text.slice(0, 8000));
      }
    }
    return { ...(normalized.structuredContent && record(normalized.structuredContent)
      ? { structuredContent: normalized.structuredContent } : {}),
    ...(texts.length ? { text: texts } : {}),
    ...(normalized.isError === true ? { isError: true } : {}) };
  }

  private async initialize(server: AgentMcpServerRecord, headers: Record<string, string>) {
    const response = await this.post(server, headers, {
      jsonrpc: "2.0", id: 1, method: "initialize",
      params: { protocolVersion: PROTOCOL_VERSION, capabilities: {}, clientInfo: { name: "dinqor-agent", version: "1.0.0" } },
    });
    const payload = await readBody(response);
    if (!record(payload) || record(payload.error) || !record(payload.result)) throw new Error("MCP 初始化握手失败");
    const version = typeof response.headers.get("MCP-Protocol-Version") === "string"
      ? response.headers.get("MCP-Protocol-Version")! : PROTOCOL_VERSION;
    const sessionId = response.headers.get("Mcp-Session-Id");
    await this.post(server, headers, { jsonrpc: "2.0", method: "notifications/initialized" }, sessionId, version);
    return { sessionId, version };
  }

  private async rpc(
    server: AgentMcpServerRecord,
    headers: Record<string, string>,
    sessionId: string | null,
    version: string,
    id: number,
    method: string,
    params: Record<string, unknown>,
  ): Promise<unknown> {
    const response = await this.post(server, headers, { jsonrpc: "2.0", id, method, params }, sessionId, version);
    const payload = await readBody(response);
    if (!record(payload) || (payload.id !== id && payload.id !== String(id))) throw new Error("MCP 响应 ID 不匹配");
    if (record(payload.error)) throw new Error("MCP 远端返回了协议错误");
    return payload.result;
  }

  private async post(
    server: AgentMcpServerRecord,
    headers: Record<string, string>,
    body: Record<string, unknown>,
    sessionId?: string | null,
    version?: string,
  ): Promise<Response> {
    const endpoint = await assertPublicEndpoint(server.endpointUrl);
    const requestHeaders = new Headers({
      accept: "application/json, text/event-stream",
      "content-type": "application/json",
      ...headers,
    });
    if (sessionId) requestHeaders.set("Mcp-Session-Id", sessionId);
    if (version) requestHeaders.set("MCP-Protocol-Version", version);
    let response: Response;
    try {
      response = await fetch(endpoint, {
        method: "POST", headers: requestHeaders, body: JSON.stringify(body),
        redirect: "manual", signal: AbortSignal.timeout(30_000),
      });
    } catch { throw new Error("无法连接 MCP 服务器"); }
    if (response.status >= 300 && response.status < 400) throw new Error("MCP 服务器重定向已拒绝");
    if (!response.ok && !(body.id === undefined && response.status === 202)) {
      throw new Error(`MCP 服务器返回 HTTP ${response.status}`);
    }
    return response;
  }
}
