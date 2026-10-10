import type { IdentityUser } from "../interfaces/identity-repository.js";
import type { UserAdminRecord, UserAdminRepository } from "../interfaces/user-admin-repository.js";

export interface UserAdminPage {
  records: UserAdminRecord[];
  total: number;
  current: number;
  pages: number;
  size: number;
  hasMore: boolean;
}

export type ChangeUserStatusResult =
  | { kind: "updated"; user: UserAdminRecord }
  | { kind: "self" }
  | { kind: "missing" }
  | { kind: "admin" };

export class UserAdminService {
  constructor(private readonly users: UserAdminRepository) {}

  async list(page: number, size: number, keyword: string): Promise<UserAdminPage> {
    const offset = (page - 1) * size;
    const [records, total] = await Promise.all([
      this.users.searchUsers({ offset, limit: size, keyword }),
      this.users.countUsers(keyword),
    ]);
    const pages = Math.ceil(total / size);
    return { records, total, current: page, pages, size, hasMore: page < pages };
  }

  async changeStatus(
    operator: IdentityUser,
    targetId: number,
    status: 0 | 1,
  ): Promise<ChangeUserStatusResult> {
    if (operator.id === targetId) return { kind: "self" };
    const role = await this.users.findUserRole(targetId);
    if (!role) return { kind: "missing" };
    if (role === "ADMIN") return { kind: "admin" };
    await this.users.updateStatus(targetId, status);
    const user = await this.users.findUser(targetId);
    return user ? { kind: "updated", user } : { kind: "missing" };
  }
}
