import type { IdentityUser } from "../interfaces/identity-repository.js";
import { effectiveIdentityTier } from "../services/identity-tier.js";

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
    tier: effectiveIdentityTier(user),
    tierExpiresAt: localDateTime(user.tierExpiresAt),
    email: user.email,
    createdAt: localDateTime(user.createdAt),
  };
}
