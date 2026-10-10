export interface IdentityProfileRepository {
  findUsernameOwner(username: string, exceptUserId: number): Promise<number | null>;
  updateProfile(
    userId: number,
    username: string,
    nickname: string,
    avatar: string | null,
  ): Promise<boolean>;
}
