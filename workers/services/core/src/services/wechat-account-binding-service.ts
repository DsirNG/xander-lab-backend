import type { IdentityUser } from "../interfaces/identity-repository.js";
import type { IdentityAccountService } from "./identity-account-service.js";

export type WechatAccountBindingResult =
  | { kind: "ready"; user: IdentityUser }
  | {
    kind:
      | "account_disabled"
      | "wechat_email_conflict"
      | "wechat_bound_to_other_email"
      | "account_conflict"
      | "migration_required"
      | "password_service_unconfigured"
      | "binding_failed"
      | "session_expired";
  };

export class WechatAccountBindingService {
  constructor(private readonly accounts: IdentityAccountService) {}

  async bindPendingWechat(
    openid: string,
    email: string,
    passwordPepper: string | null,
  ): Promise<WechatAccountBindingResult> {
    let user = await this.accounts.findByOpenId(openid);
    const emailUser = await this.accounts.findByEmailOrUsername(email);
    if (user && user.status !== 1) return { kind: "account_disabled" };
    if (emailUser && emailUser.status !== 1) return { kind: "account_disabled" };
    if (user && emailUser && user.id !== emailUser.id) {
      if (user.email) return { kind: "account_conflict" };
      if (emailUser.openid && emailUser.openid !== openid) return { kind: "wechat_email_conflict" };
      return { kind: "migration_required" };
    }
    if (emailUser) {
      if (emailUser.openid && emailUser.openid !== openid) return { kind: "wechat_email_conflict" };
      if (!emailUser.openid) {
        await this.accounts.attachOpenIdIfEmpty(openid, emailUser.id);
        const bound = await this.accounts.findByOpenId(openid);
        if (!bound || bound.id !== emailUser.id) return { kind: "binding_failed" };
        user = bound;
      } else user = emailUser;
    } else if (user) {
      if (user.email && user.email !== email) return { kind: "wechat_bound_to_other_email" };
      user = await this.accounts.attachEmailIfAvailable(email, user.id, openid);
    } else {
      if (!passwordPepper) return { kind: "password_service_unconfigured" };
      user = await this.accounts.createWechatAccount(openid, email, passwordPepper);
    }
    return user ? { kind: "ready", user } : { kind: "binding_failed" };
  }

  async skipPendingWechat(
    openid: string,
    passwordPepper: string | null,
  ): Promise<WechatAccountBindingResult> {
    let user = await this.accounts.findByOpenId(openid);
    if (!user) {
      if (!passwordPepper) return { kind: "password_service_unconfigured" };
      user = await this.accounts.createWechatAccount(openid, null, passwordPepper);
    }
    return !user || user.status !== 1 ? { kind: "account_disabled" } : { kind: "ready", user };
  }

  async bindEmailToExistingWechatUser(
    email: string,
    user: IdentityUser,
  ): Promise<WechatAccountBindingResult> {
    const openid = user.openid;
    if (!openid) return { kind: "binding_failed" };
    const existing = await this.accounts.findByEmailOrUsername(email);
    if (existing && existing.id !== user.id) {
      if (existing.status !== 1) return { kind: "account_disabled" };
      if (existing.openid && existing.openid !== openid) return { kind: "wechat_email_conflict" };
      return { kind: "migration_required" };
    }
    const updated = await this.accounts.attachEmailToActiveUnboundUser(email, user.id);
    return updated ? { kind: "ready", user: updated } : { kind: "session_expired" };
  }
}
