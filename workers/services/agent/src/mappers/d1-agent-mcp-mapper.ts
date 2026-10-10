import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  AgentMcpRemoteTool,
  AgentMcpServerInput,
  AgentMcpServerRecord,
  AgentMcpServerRepository,
} from "../interfaces/agent-mcp-repository.js";

const COLUMNS = `id, owner_user_id AS ownerUserId, server_key AS serverKey,
  display_name AS displayName, endpoint_url AS endpointUrl, headers_encrypted AS headersEncrypted,
  enabled, tools_json AS toolsJson, last_status AS lastStatus, last_error AS lastError,
  last_connected_at AS lastConnectedAt, created_at AS createdAt, updated_at AS updatedAt`;

interface McpRow extends Omit<AgentMcpServerRecord, "enabled" | "tools"> {
  enabled: number;
  toolsJson: string;
}

function mapRow(row: McpRow | null): AgentMcpServerRecord | null {
  if (!row || !Number.isSafeInteger(row.id) || !Number.isSafeInteger(row.ownerUserId)) return null;
  let tools: AgentMcpRemoteTool[] = [];
  try {
    const parsed: unknown = JSON.parse(row.toolsJson || "[]");
    if (Array.isArray(parsed)) tools = parsed.filter(isRemoteTool);
  } catch { /* corrupted cache is a cache miss */ }
  return { ...row, enabled: row.enabled === 1, tools };
}

function isRemoteTool(value: unknown): value is AgentMcpRemoteTool {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const row = value as Record<string, unknown>;
  return typeof row.name === "string" && typeof row.description === "string"
    && !!row.inputSchema && typeof row.inputSchema === "object" && !Array.isArray(row.inputSchema);
}

/** Agent-owned D1 persistence for owner-scoped outbound MCP server settings. */
export class D1AgentMcpMapper implements AgentMcpServerRepository {
  constructor(private readonly db: D1Binding) {}

  async list(ownerUserId: number): Promise<AgentMcpServerRecord[]> {
    const rows = await this.db.prepare(`SELECT ${COLUMNS} FROM agent_mcp_server
      WHERE owner_user_id = ? ORDER BY server_key`).bind(ownerUserId).all<McpRow>();
    return rows.results.map(mapRow).filter((row): row is AgentMcpServerRecord => row !== null);
  }

  async listEnabledVisibleTo(userId: number): Promise<AgentMcpServerRecord[]> {
    const rows = await this.db.prepare(`SELECT ${COLUMNS} FROM agent_mcp_server
      WHERE enabled = 1 AND owner_user_id IN (0, ?) ORDER BY owner_user_id, server_key`)
      .bind(userId).all<McpRow>();
    return rows.results.map(mapRow).filter((row): row is AgentMcpServerRecord => row !== null);
  }

  async find(id: number, ownerUserId: number): Promise<AgentMcpServerRecord | null> {
    return mapRow(await this.db.prepare(`SELECT ${COLUMNS} FROM agent_mcp_server
      WHERE id = ? AND owner_user_id = ? LIMIT 1`).bind(id, ownerUserId).first<McpRow>());
  }

  async findByKey(ownerUserId: number, serverKey: string): Promise<AgentMcpServerRecord | null> {
    return mapRow(await this.db.prepare(`SELECT ${COLUMNS} FROM agent_mcp_server
      WHERE owner_user_id = ? AND server_key = ? LIMIT 1`).bind(ownerUserId, serverKey).first<McpRow>());
  }

  async insert(input: AgentMcpServerInput): Promise<AgentMcpServerRecord> {
    const row = await this.db.prepare(`INSERT INTO agent_mcp_server (
        owner_user_id, server_key, display_name, endpoint_url, headers_encrypted, enabled
      ) VALUES (?, ?, ?, ?, ?, ?) RETURNING ${COLUMNS}`)
      .bind(input.ownerUserId, input.serverKey, input.displayName, input.endpointUrl,
        input.headersEncrypted, input.enabled ? 1 : 0).first<McpRow>();
    const result = mapRow(row);
    if (!result) throw new Error("Agent MCP server was not persisted");
    return result;
  }

  async update(
    id: number,
    ownerUserId: number,
    input: Omit<AgentMcpServerInput, "ownerUserId" | "serverKey">,
  ): Promise<boolean> {
    const result = await this.db.prepare(`UPDATE agent_mcp_server SET
        display_name = ?, endpoint_url = ?, headers_encrypted = ?, enabled = ?,
        last_status = NULL, last_error = NULL,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND owner_user_id = ?`)
      .bind(input.displayName, input.endpointUrl, input.headersEncrypted, input.enabled ? 1 : 0, id, ownerUserId).run();
    return result.meta.changes === 1;
  }

  async updateProbe(id: number, ownerUserId: number, values: {
    tools?: AgentMcpRemoteTool[];
    lastStatus: "OK" | "FAILED";
    lastError: string | null;
    lastConnectedAt: string | null;
  }): Promise<boolean> {
    const result = await this.db.prepare(`UPDATE agent_mcp_server SET
        tools_json = COALESCE(?, tools_json), last_status = ?, last_error = ?, last_connected_at = ?,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND owner_user_id = ?`)
      .bind(values.tools === undefined ? null : JSON.stringify(values.tools), values.lastStatus,
        values.lastError, values.lastConnectedAt, id, ownerUserId).run();
    return result.meta.changes === 1;
  }

  async delete(id: number, ownerUserId: number): Promise<boolean> {
    const result = await this.db.prepare("DELETE FROM agent_mcp_server WHERE id = ? AND owner_user_id = ?")
      .bind(id, ownerUserId).run();
    return result.meta.changes === 1;
  }
}
