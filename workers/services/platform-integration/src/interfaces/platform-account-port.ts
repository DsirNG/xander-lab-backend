export interface PlatformAccountProfile {
  username: string | null;
  nickname: string | null;
  avatarUrl: string | null;
}

/** Reads the account identified by the credential, without a target username. */
export interface PlatformAccountPort {
  getCurrentAccount(credential: Record<string, string>): Promise<PlatformAccountProfile>;
}
