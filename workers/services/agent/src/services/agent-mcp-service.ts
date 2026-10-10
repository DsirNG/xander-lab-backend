import type { AgentMcpConnection } from "../interfaces/agent-mcp-connection.js";
import type {
  AgentMcpRemoteTool,
  AgentMcpServerInput,
  AgentMcpServerRecord,
  AgentMcpServerRepository,
} from "../interfaces/agent-mcp-repository.js";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";

const PLATFORM_OWNER_ID = 0;
const HEADER_NAME = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/;

export class AgentMcpError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

export interface AgentMcpToolView extends AgentMcpRemoteTool {
  serverId: number;
  serverKey: string;
  serverName: string;
  toolName: string;
  fullName: string;
}

function safeId(id: unknown): id is number {
  return typeof id === "number" && Number.isSafeInteger(id) && id > 0;
}

function composeName(serverKey: string, name: string): string {
  return `mcp__${serverKey}__${name.replace(/[^A-Za-z0-9_-]/g, "_")}`;
}

function validEndpoint(value: unknown): string {
  if (typeof value !== "string" || value.length > 500) throw new AgentMcpError(400, "MCP 端点地址格式不正确");
  let url: URL;
  try { url = new URL(value.trim()); } catch { throw new AgentMcpError(400, "MCP 端点地址不是有效 URL"); }
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (url.protocol !== "https:" || url.username || url.password || url.hash
    || !host || host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")
    || host === "metadata.google.internal" || host.includes("%")) {
    throw new AgentMcpError(400, "MCP 端点必须是无内嵌凭据的公网 HTTPS URL");
  }
  if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host) || host.includes(":")) {
    throw new AgentMcpError(400, "MCP 端点必须使用公网 DNS 主机名");
  }
  return url.toString();
}

function validateHeaders(value: unknown): Record<string, string> | null {
  if (value === undefined || value === null) return null;
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new AgentMcpError(400, "请求头必须是对象");
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length > 20) throw new AgentMcpError(400, "请求头最多包含 20 项");
  const result: Record<string, string> = {};
  for (const [name, raw] of entries) {
    if (!HEADER_NAME.test(name) || name.length > 120 || typeof raw !== "string" || raw.length > 2000
      || /[\r\n]/.test(raw)) throw new AgentMcpError(400, "请求头名称或值格式不正确");
    result[name] = raw;
  }
  return result;
}

function validRequest(value: unknown): Omit<AgentMcpServerInput, "ownerUserId" | "headersEncrypted"> & { headers: unknown } {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new AgentMcpError(400, "MCP 服务器参数格式不正确");
  const row = value as Record<string, unknown>;
  const serverKey = typeof row.serverKey === "string" ? row.serverKey.trim() : "";
  const displayName = typeof row.displayName === "string" ? row.displayName.trim() : "";
  if (!/^[a-z][a-z0-9-]{1,19}$/.test(serverKey)) throw new AgentMcpError(400, "serverKey 必须为 2-20 位小写字母、数字或短横线");
  if (!displayName || displayName.length > 120) throw new AgentMcpError(400, "服务器名称不能为空且最多 120 个字符");
  if (row.enabled !== undefined && typeof row.enabled !== "boolean") throw new AgentMcpError(400, "enabled 必须是布尔值");
  const allowed = ["serverKey", "displayName", "endpointUrl", "headers", "enabled"];
  if (Object.keys(row).some((key) => !allowed.includes(key))) throw new AgentMcpError(400, "MCP 服务器参数包含未知字段");
  return {
    serverKey,
    displayName,
    endpointUrl: validEndpoint(row.endpointUrl),
    enabled: row.enabled === true,
    headers: row.headers,
  };
}

function toView(server: AgentMcpServerRecord) {
  return {
    id: server.id,
    ownerUserId: server.ownerUserId,
    serverKey: server.serverKey,
    displayName: server.displayName,
    endpointUrl: server.endpointUrl,
    enabled: server.enabled,
    platformOwned: server.ownerUserId === PLATFORM_OWNER_ID,
    headersConfigured: !!server.headersEncrypted,
    tools: server.tools,
    lastStatus: server.lastStatus,
    lastError: server.lastError,
    lastConnectedAt: server.lastConnectedAt,
  };
}

/** Owns MCP server settings, encrypted credentials, probe results, and the dynamic tool catalog. */
export class AgentMcpService {
  constructor(
    private readonly repository: AgentMcpServerRepository,
    private readonly connection: AgentMcpConnection,
    private readonly env: AgentEnvironment,
  ) {}

  async list(ownerUserId: number) {
    return (await this.repository.list(ownerUserId)).map(toView);
  }

  async create(ownerUserId: number, value: unknown) {
    this.requireEnabled();
    const input = validRequest(value);
    if (await this.repository.findByKey(ownerUserId, input.serverKey)) {
      throw new AgentMcpError(409, `已存在同名标识的服务器：${input.serverKey}`);
    }
    const headers = validateHeaders(input.headers);
    const created = await this.repository.insert({
      ownerUserId,
      serverKey: input.serverKey,
      displayName: input.displayName,
      endpointUrl: input.endpointUrl,
      headersEncrypted: headers === null ? null : await this.encryptHeaders(headers),
      enabled: input.enabled,
    });
    return toView(created);
  }

  async update(id: number, ownerUserId: number, value: unknown) {
    this.requireEnabled();
    const current = await this.requireOwned(id, ownerUserId);
    const input = validRequest(value);
    if (input.serverKey !== current.serverKey) throw new AgentMcpError(400, "服务器标识不可修改，请新建一台服务器");
    const headers = validateHeaders(input.headers);
    const headersEncrypted = headers === null ? current.headersEncrypted
      : Object.keys(headers).length === 0 ? null : await this.encryptHeaders(headers);
    const updated = await this.repository.update(id, ownerUserId, {
      displayName: input.displayName,
      endpointUrl: input.endpointUrl,
      headersEncrypted,
      enabled: input.enabled,
    });
    if (!updated) throw new AgentMcpError(404, "MCP 服务器不存在");
    const result = await this.requireOwned(id, ownerUserId);
    return toView(result);
  }

  async delete(id: number, ownerUserId: number): Promise<void> {
    await this.requireOwned(id, ownerUserId);
    if (!await this.repository.delete(id, ownerUserId)) throw new AgentMcpError(404, "MCP 服务器不存在");
  }

  async probe(id: number, ownerUserId: number) {
    this.requireEnabled();
    const server = await this.requireOwned(id, ownerUserId);
    try {
      const tools = await this.connection.listTools(server, await this.decryptHeaders(server.headersEncrypted));
      await this.repository.updateProbe(id, ownerUserId, {
        tools: tools.slice(0, 50),
        lastStatus: "OK",
        lastError: tools.length > 50 ? `远端工具共 ${tools.length} 个，只并入前 50 个` : null,
        lastConnectedAt: new Date().toISOString(),
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "MCP 连接失败";
      await this.repository.updateProbe(id, ownerUserId, {
        lastStatus: "FAILED", lastError: message.slice(0, 500), lastConnectedAt: server.lastConnectedAt,
      });
    }
    return toView(await this.requireOwned(id, ownerUserId));
  }

  async toolsForUser(userId: number): Promise<AgentMcpToolView[]> {
    if (this.env.AGENT_MCP_CLIENT_ENABLED !== "true") return [];
    const servers = await this.repository.listEnabledVisibleTo(userId);
    const result: AgentMcpToolView[] = [];
    for (const server of servers) {
      for (const remote of server.tools) {
        const fullName = composeName(server.serverKey, remote.name);
        if (fullName.length > 64) continue;
        result.push({
          ...remote,
          serverId: server.id,
          serverKey: server.serverKey,
          serverName: server.displayName,
          toolName: remote.name,
          fullName,
          description: `${remote.description.slice(0, 400)}（第三方 MCP 服务器「${server.displayName}」提供；调用会离开本平台）`,
          inputSchema: remote.inputSchema,
        });
      }
    }
    return result;
  }

  async callApprovedTool(userId: number, serverId: number, toolName: string, argumentsValue: unknown): Promise<unknown> {
    if (!safeId(serverId)) throw new AgentMcpError(404, "MCP 服务器不存在");
    this.requireEnabled();
    const server = await this.repository.find(serverId, userId)
      ?? await this.repository.find(serverId, PLATFORM_OWNER_ID);
    if (!server || !server.enabled) throw new AgentMcpError(409, "MCP 服务器不存在或已停用");
    if (!server.tools.some((tool) => tool.name === toolName)) throw new AgentMcpError(409, "MCP 工具目录已变化，请重新发起");
    return this.connection.callTool(server, await this.decryptHeaders(server.headersEncrypted), toolName, argumentsValue);
  }

  private async requireOwned(id: number, ownerUserId: number): Promise<AgentMcpServerRecord> {
    const value = await this.repository.find(id, ownerUserId);
    if (!value) throw new AgentMcpError(404, "MCP 服务器不存在");
    return value;
  }

  private requireEnabled(): void {
    if (this.env.AGENT_MCP_CLIENT_ENABLED !== "true") throw new AgentMcpError(503, "MCP 客户端功能未开启");
    const secret = this.env.AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY;
    if (!secret) throw new AgentMcpError(503, "MCP 凭据加密密钥尚未配置");
    try {
      const key = Uint8Array.from(atob(secret), (character) => character.charCodeAt(0));
      if (key.byteLength !== 32) throw new Error("bad key length");
    } catch { throw new AgentMcpError(503, "MCP 凭据加密密钥必须是 Base64 编码的 32 字节密钥"); }
  }

  private async encryptionKey(): Promise<CryptoKey> {
    const secret = this.env.AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY;
    if (!secret) throw new AgentMcpError(503, "MCP 凭据加密密钥尚未配置");
    let raw: Uint8Array;
    try { raw = Uint8Array.from(atob(secret), (character) => character.charCodeAt(0)); }
    catch { throw new AgentMcpError(503, "MCP 凭据加密密钥格式不正确"); }
    if (raw.byteLength !== 32) throw new AgentMcpError(503, "MCP 凭据加密密钥必须是 Base64 编码的 32 字节密钥");
    const keyBytes = new Uint8Array(raw.byteLength);
    keyBytes.set(raw);
    return crypto.subtle.importKey("raw", keyBytes.buffer as ArrayBuffer,
      { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
  }

  private async encryptHeaders(headers: Record<string, string>): Promise<string> {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await this.encryptionKey();
    const bytes = new TextEncoder().encode(JSON.stringify(headers));
    const encrypted = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, bytes));
    const encode = (value: Uint8Array) => btoa(String.fromCharCode(...value));
    return `${encode(iv)}.${encode(encrypted)}`;
  }

  private async decryptHeaders(ciphertext: string | null): Promise<Record<string, string>> {
    if (!ciphertext) return {};
    const [ivText, dataText] = ciphertext.split(".", 2);
    if (!ivText || !dataText) throw new AgentMcpError(503, "MCP 凭据密文格式不正确");
    try {
      const decode = (value: string) => Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
      const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv: decode(ivText) },
        await this.encryptionKey(), decode(dataText));
      const parsed: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(plaintext));
      return validateHeaders(parsed) ?? {};
    } catch { throw new AgentMcpError(503, "无法解密 MCP 服务器凭据"); }
  }
}
