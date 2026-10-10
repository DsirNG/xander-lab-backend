export interface AgentAuthRepository {
  resolveActiveUser(accessToken: string): Promise<number | null | Response>;
}
