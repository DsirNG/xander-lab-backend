import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  UserAdminRecord,
  UserAdminRepository,
  UserAdminSearch,
} from "../interfaces/user-admin-repository.js";

export class D1UserAdminMapper implements UserAdminRepository {
  constructor(private readonly db: D1Binding) {}

  async searchUsers(query: UserAdminSearch): Promise<UserAdminRecord[]> {
    const { filter, bindings } = this.filter(query.keyword);
    const result = await this.db.prepare(`
      SELECT id, username, nickname, email, role, status,
             created_at AS createdAt
      FROM sys_user${filter}
      ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
    `).bind(...bindings, query.limit, query.offset).all<UserAdminRecord>();
    return result.results ?? [];
  }

  async countUsers(keyword: string): Promise<number> {
    const { filter, bindings } = this.filter(keyword);
    const result = await this.db.prepare(`SELECT COUNT(*) AS total FROM sys_user${filter}`)
      .bind(...bindings)
      .first<{ total: number }>();
    return result?.total ?? 0;
  }

  async findUserRole(userId: number): Promise<string | null> {
    const result = await this.db.prepare("SELECT role FROM sys_user WHERE id = ? LIMIT 1")
      .bind(userId)
      .first<{ role: string }>();
    return result?.role ?? null;
  }

  async updateStatus(userId: number, status: 0 | 1): Promise<void> {
    const now = new Date().toISOString();
    await this.db.batch([
      this.db.prepare(`
        UPDATE sys_user
        SET status = ?, auth_version = auth_version + 1,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND status <> ?
      `).bind(status, userId, status),
      this.db.prepare(`
        UPDATE auth_refresh_session
        SET status = 'REVOKED', revoked_at = ?, revoked_reason = ?,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE account_id = ? AND status = 'ACTIVE'
      `).bind(now, status === 0 ? "ADMIN_DISABLE" : "ADMIN_ENABLE", userId),
    ]);
  }

  findUser(userId: number): Promise<UserAdminRecord | null> {
    return this.db.prepare(`
      SELECT id, username, nickname, email, role, status, created_at AS createdAt
      FROM sys_user WHERE id = ? LIMIT 1
    `).bind(userId).first<UserAdminRecord>();
  }

  private filter(keyword: string): { filter: string; bindings: string[] } {
    if (!keyword) return { filter: "", bindings: [] };
    const escaped = keyword.replace(/[\\%_]/g, "\\$&");
    const query = `%${escaped}%`;
    return {
      filter: " WHERE username LIKE ? ESCAPE '\\' COLLATE NOCASE"
        + " OR nickname LIKE ? ESCAPE '\\' COLLATE NOCASE"
        + " OR email LIKE ? ESCAPE '\\' COLLATE NOCASE",
      bindings: [query, query, query],
    };
  }
}
