import type { IdentityProfileRepository } from "../interfaces/identity-profile-repository.js";
import type { IdentityUser } from "../interfaces/identity-repository.js";

export interface IdentityProfileInput {
  username?: unknown;
  nickname?: unknown;
  avatar?: unknown;
}

export type IdentityProfileUpdate =
  | { kind: "updated"; user: IdentityUser }
  | { kind: "invalid"; message: string }
  | { kind: "username_taken" }
  | { kind: "inactive" };

export class IdentityProfileService {
  constructor(private readonly profiles: IdentityProfileRepository) {}

  async update(user: IdentityUser, input: IdentityProfileInput): Promise<IdentityProfileUpdate> {
    if (typeof input.nickname !== "string" || !input.nickname.trim()) {
      return { kind: "invalid", message: "昵称不能为空" };
    }
    const nickname = input.nickname.trim();
    if (nickname.length > 30) return { kind: "invalid", message: "昵称长度不能超过 30 个字符" };

    let username = user.username;
    if (input.username !== undefined && input.username !== null) {
      if (typeof input.username !== "string") return { kind: "invalid", message: "用户名格式不正确" };
      username = input.username.trim();
      if (username.length < 3 || username.length > 30) {
        return { kind: "invalid", message: "用户名长度需在 3-30 个字符之间" };
      }
      if (!/^[A-Za-z0-9_@.+-]+$/.test(username)) {
        return { kind: "invalid", message: "用户名只能包含字母、数字及 _@.+-" };
      }
    }

    let avatar = user.avatar;
    if (input.avatar !== undefined && input.avatar !== null) {
      if (typeof input.avatar !== "string") return { kind: "invalid", message: "头像地址格式不正确" };
      const candidate = input.avatar.trim();
      if (candidate) {
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
          return { kind: "invalid", message: "头像地址必须以 http(s):// 开头" };
        }
        if (candidate.length > 255) return { kind: "invalid", message: "头像地址过长" };
        avatar = candidate;
      }
    }

    const owner = await this.profiles.findUsernameOwner(username, user.id);
    if (owner !== null) return { kind: "username_taken" };
    if (!await this.profiles.updateProfile(user.id, username, nickname, avatar)) return { kind: "inactive" };
    return { kind: "updated", user: { ...user, username, nickname, avatar } };
  }
}
