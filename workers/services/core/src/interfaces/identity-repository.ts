export interface IdentityUser {
  id: number;
  username: string;
  nickname: string | null;
  email: string | null;
  avatar: string | null;
  openid?: string | null;
  role: string;
  tier: string;
  tierExpiresAt: string | null;
  status: number;
  createdAt: string;
  authVersion?: number;
  password?: string | null;
}

export interface IdentityRepository {
  findActiveUserForToken(
    userId: number,
    tokenFingerprint: string,
    now: number,
  ): Promise<IdentityUser | null>;
}
