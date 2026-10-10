import type { IdentityUser } from "./identity-repository.js";

export interface IdentitySocialRepository {
  findByOpenId(openid: string): Promise<IdentityUser | null>;
  createWechatUser(
    username: string,
    passwordHash: string,
    nickname: string,
    email: string | null,
    avatar: string,
    openid: string,
  ): Promise<IdentityUser | null>;
  attachOpenIdIfEmpty(openid: string, userId: number): Promise<void>;
  attachEmailIfAvailable(email: string, userId: number): Promise<void>;
  attachEmailToActiveUnboundUser(email: string, userId: number): Promise<void>;
  findById(userId: number): Promise<IdentityUser | null>;
}
