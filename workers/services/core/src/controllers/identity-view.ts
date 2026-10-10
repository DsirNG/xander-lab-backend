import type { IdentityUser } from "../interfaces/identity-repository.js";

function localDateTime(value: string | null): string | null {
  if (!value) return null;
  return value.includes("T") ? value : value.replace(" ", "T");
}

export function userInfo(user: IdentityUser) {
  return {
    username: user.username,
    nickname: user.nickname,
    avatar: user.avatar,
    role: user.role,
    tier: user.tier,
    tierExpiresAt: localDateTime(user.tierExpiresAt),
    email: user.email,
    createdAt: localDateTime(user.createdAt),
  };
}
