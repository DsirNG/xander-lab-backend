import { hashPassword, verifyPassword } from "../password.js";
import type { IdentityAccountRepository } from "../interfaces/identity-account-repository.js";
import type { IdentitySocialRepository } from "../interfaces/identity-social-repository.js";
import type { IdentityUser } from "../interfaces/identity-repository.js";

export class IdentityAccountService {
  constructor(
    private readonly accounts: IdentityAccountRepository,
    private readonly social: IdentitySocialRepository,
  ) {}

  findByEmailOrUsername(email: string): Promise<IdentityUser | null> {
    return this.accounts.findByEmailOrUsername(email);
  }

  async createAccount(
    email: string,
    password: string,
    nickname: string,
    pepper: string,
  ): Promise<IdentityUser | null> {
    const passwordHash = await hashPassword(password, pepper);
    const avatar = `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(email)}`;
    return this.accounts.createAccount(email, passwordHash, nickname, avatar);
  }

  async authenticatePassword(
    account: string,
    password: string,
    pepper: string,
  ): Promise<IdentityUser | null> {
    const user = await this.accounts.findForPasswordLogin(account);
    if (!user || !await verifyPassword(password, user.password ?? null, pepper)) return null;
    return user;
  }

  async resetPassword(userId: number, newPassword: string, pepper: string): Promise<void> {
    await this.accounts.updatePasswordForActiveUser(userId, await hashPassword(newPassword, pepper));
  }

  async changePassword(
    userId: number,
    currentPassword: string,
    newPassword: string,
    pepper: string,
  ): Promise<boolean> {
    const encoded = await this.accounts.findActivePassword(userId);
    if (!encoded || !await verifyPassword(currentPassword, encoded, pepper)) return false;
    await this.accounts.updatePasswordForActiveUser(userId, await hashPassword(newPassword, pepper));
    return true;
  }

  findByOpenId(openid: string): Promise<IdentityUser | null> {
    return this.social.findByOpenId(openid);
  }

  async createWechatAccount(
    openid: string,
    email: string | null,
    pepper: string,
  ): Promise<IdentityUser | null> {
    const username = email ?? `wx_${await this.openIdSuffix(openid)}`;
    const nickname = email
      ? email.split("@")[0]
      : `微信用户${1000 + crypto.getRandomValues(new Uint32Array(1))[0] % 9000}`;
    const passwordHash = await hashPassword(this.randomHex(32), pepper);
    const avatar = `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(username)}`;
    const inserted = await this.social.createWechatUser(username, passwordHash, nickname, email, avatar, openid);
    return inserted ?? this.social.findByOpenId(openid);
  }

  attachOpenIdIfEmpty(openid: string, userId: number): Promise<void> {
    return this.social.attachOpenIdIfEmpty(openid, userId);
  }

  async attachEmailIfAvailable(email: string, userId: number, openid: string): Promise<IdentityUser | null> {
    await this.social.attachEmailIfAvailable(email, userId);
    return this.social.findByOpenId(openid);
  }

  async attachEmailToActiveUnboundUser(email: string, userId: number): Promise<IdentityUser | null> {
    await this.social.attachEmailToActiveUnboundUser(email, userId);
    return this.social.findById(userId);
  }

  private randomHex(byteLength: number): string {
    return Array.from(crypto.getRandomValues(new Uint8Array(byteLength)),
      (byte) => byte.toString(16).padStart(2, "0")).join("");
  }

  private async openIdSuffix(openid: string): Promise<string> {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(openid));
    return Array.from(new Uint8Array(digest).slice(0, 8),
      (byte) => byte.toString(16).padStart(2, "0")).join("");
  }
}
