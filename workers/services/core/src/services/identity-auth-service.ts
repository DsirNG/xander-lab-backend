import { verifyAccessToken } from "../auth.js";
import type { IdentityRepository, IdentityUser } from "../interfaces/identity-repository.js";

export type AccessAuthentication =
  | { kind: "unavailable" }
  | { kind: "invalid" }
  | { kind: "authenticated"; user: IdentityUser };

export async function fingerprintAccessToken(token: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export class IdentityAuthService {
  constructor(private readonly identities: IdentityRepository) {}

  async authenticateAccessToken(
    token: string,
    jwtSecret: string | undefined,
  ): Promise<AccessAuthentication> {
    if (!jwtSecret || new TextEncoder().encode(jwtSecret).byteLength < 32) {
      return { kind: "unavailable" };
    }
    const claims = await verifyAccessToken(token, jwtSecret);
    if (!claims) return { kind: "invalid" };

    const user = await this.identities.findActiveUserForToken(
      Number(claims.sub),
      await fingerprintAccessToken(token),
      Date.now(),
    );
    const tokenVersion = claims.ver === undefined ? 0 : claims.ver;
    if (!user || user.status !== 1 || !Number.isSafeInteger(tokenVersion)
      || tokenVersion !== (user.authVersion ?? 0)) {
      return { kind: "invalid" };
    }
    return { kind: "authenticated", user };
  }
}
