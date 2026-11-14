import type { AgentWebSettingsRepository } from "../interfaces/agent-web-settings-repository.js";
import type { AgentWebResearchEnvironment, AgentWebResearchPort } from "../interfaces/agent-web-research-port.js";

export interface AgentWebSettingsEnvironment extends AgentWebResearchEnvironment {
  AGENT_WEB_CREDENTIAL_ENCRYPTION_KEY?: string;
  AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY?: string;
}
export class AgentWebSettingsError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

const encode = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
const decode = (text: string) => Uint8Array.from(atob(text), char => char.charCodeAt(0));

/** Admin settings, write-only API keys and turn-scoped credential resolution. */
export class AgentWebSettingsService {
  constructor(private readonly repository: AgentWebSettingsRepository, private readonly env: AgentWebSettingsEnvironment,
    private readonly provider: (key: string) => AgentWebResearchPort) {}

  async view() {
    const row = await this.repository.find();
    return { provider: "tavily", enabled: row ? row.enabled : this.env.AGENT_WEB_SEARCH_ENABLED !== "false" && !!this.env.TAVILY_API_KEY?.trim(),
      apiKeyConfigured: row ? !!row.encryptedApiKey : !!this.env.TAVILY_API_KEY?.trim(),
      encryptionReady: !!this.keyBytes(), source: row ? "admin" : "environment", updatedAt: row?.updatedAt ?? null };
  }

  async save(value: unknown, adminId: number) {
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new AgentWebSettingsError(400, "搜索配置格式不正确");
    const input = value as Record<string, unknown>;
    if (typeof input.enabled !== "boolean" || Object.keys(input).some(key => !["enabled", "apiKey", "clearApiKey"].includes(key))
      || (input.apiKey !== undefined && typeof input.apiKey !== "string")
      || (input.clearApiKey !== undefined && typeof input.clearApiKey !== "boolean")) throw new AgentWebSettingsError(400, "搜索配置参数不合法");
    const key = typeof input.apiKey === "string" ? input.apiKey.trim() : "";
    if (key && (key.length > 1000 || /\s|[\u0000-\u001f\u007f]/.test(key))) throw new AgentWebSettingsError(400, "API Key 格式不正确");
    if (key && input.clearApiKey) throw new AgentWebSettingsError(400, "不能同时更新和清除 API Key");
    const row = await this.repository.find();
    let encryptedApiKey = row?.encryptedApiKey ?? null;
    if (input.clearApiKey) encryptedApiKey = null;
    else if (key) encryptedApiKey = await this.encrypt(key);
    else if (!row && input.enabled && this.env.TAVILY_API_KEY?.trim()) encryptedApiKey = await this.encrypt(this.env.TAVILY_API_KEY.trim());
    if (input.enabled && !encryptedApiKey) throw new AgentWebSettingsError(400, "启用联网搜索前请先配置 API Key");
    await this.repository.save({ enabled: input.enabled, encryptedApiKey, updatedBy: adminId });
    return this.view();
  }

  async runtime(): Promise<{ apiKey?: string; allowNativeSearch: boolean }> {
    const row = await this.repository.find();
    // An explicit admin disable or key removal must never fall back to a paid provider.
    if (row) return { apiKey: row.enabled && row.encryptedApiKey ? await this.decrypt(row.encryptedApiKey) : undefined, allowNativeSearch: false };
    if (this.env.AGENT_WEB_SEARCH_ENABLED === "false") return { allowNativeSearch: false };
    return { apiKey: this.env.TAVILY_API_KEY?.trim() || undefined, allowNativeSearch: !this.env.TAVILY_API_KEY?.trim() };
  }

  async probe(signal: AbortSignal) {
    const { apiKey } = await this.runtime();
    if (!apiKey) throw new AgentWebSettingsError(400, "请先保存并启用联网搜索配置");
    const started = Date.now();
    try {
      const results = await this.provider(apiKey).search({ query: "Tavily search API documentation", limit: 1 }, signal);
      return { ok: true, provider: "tavily", resultCount: results.length, latencyMs: Date.now() - started };
    } catch {
      throw new AgentWebSettingsError(502, "搜索服务连接失败，请检查 API Key、账户额度或稍后重试");
    }
  }

  private keyBytes(): Uint8Array | null {
    try {
      const bytes = decode(this.env.AGENT_WEB_CREDENTIAL_ENCRYPTION_KEY ?? this.env.AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY ?? "");
      return bytes.byteLength === 32 ? bytes : null;
    } catch { return null; }
  }
  private async key() {
    const bytes = this.keyBytes();
    if (!bytes) throw new AgentWebSettingsError(503, "搜索凭据加密密钥未正确配置");
    return crypto.subtle.importKey("raw", bytes.buffer as ArrayBuffer, "AES-GCM", false, ["encrypt", "decrypt"]);
  }
  private async encrypt(value: string): Promise<string> {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode("agent-web:tavily:v1") }, await this.key(), new TextEncoder().encode(value));
    return `v1.${encode(iv)}.${encode(new Uint8Array(encrypted))}`;
  }
  private async decrypt(value: string): Promise<string> {
    try {
      const [version, iv, ciphertext, extra] = value.split(".");
      if (version !== "v1" || !iv || !ciphertext || extra) throw new Error("invalid");
      const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv: decode(iv).buffer as ArrayBuffer,
        additionalData: new TextEncoder().encode("agent-web:tavily:v1") }, await this.key(), decode(ciphertext).buffer as ArrayBuffer);
      return new TextDecoder().decode(plaintext);
    } catch { throw new AgentWebSettingsError(503, "搜索凭据无法读取，请重新保存 API Key"); }
  }
}
