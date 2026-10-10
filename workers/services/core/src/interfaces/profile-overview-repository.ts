export interface ProfileOverviewRepository {
  findPointsBalance(userId: number): Promise<number | null>;
}
