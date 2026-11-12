import type { IdentityUser } from "../interfaces/identity-repository.js";

/** Resolve the current entitlement without erasing its historical expiry. */
export function effectiveIdentityTier(user: Pick<IdentityUser, "tier" | "tierExpiresAt">, now = Date.now()): string {
  if (user.tier === "FREE" || !user.tierExpiresAt) return user.tier;
  const raw = user.tierExpiresAt.trim().replace(" ", "T");
  // Identity timestamps without an explicit offset are UTC, as stored by D1.
  const timestamp = Date.parse(/(?:Z|[+-]\d{2}:?\d{2})$/i.test(raw) ? raw : raw + "Z");
  return Number.isFinite(timestamp) && timestamp > now ? user.tier : "FREE";
}
