import type { IdentityUser } from "./identity-repository.js";

export interface IdentityAccountRepository {
  findByEmailOrUsername(email: string): Promise<IdentityUser | null>;
  findForPasswordLogin(account: string): Promise<IdentityUser | null>;
  createAccount(email: string, passwordHash: string, nickname: string, avatar: string): Promise<IdentityUser | null>;
  findActivePassword(userId: number): Promise<string | null>;
  updatePasswordForActiveUser(userId: number, passwordHash: string): Promise<void>;
}
