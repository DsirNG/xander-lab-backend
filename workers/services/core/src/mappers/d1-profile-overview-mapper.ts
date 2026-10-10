import type { D1Binding } from "@dinqor/worker-runtime";
import type { ProfileOverviewRepository } from "../interfaces/profile-overview-repository.js";

export class D1ProfileOverviewMapper implements ProfileOverviewRepository {
  constructor(private readonly db: D1Binding) {}

  async findPointsBalance(userId: number): Promise<number | null> {
    const row = await this.db.prepare("SELECT balance FROM sys_user_points WHERE user_id = ?")
      .bind(userId)
      .first<{ balance: number }>();
    return row?.balance ?? null;
  }
}
