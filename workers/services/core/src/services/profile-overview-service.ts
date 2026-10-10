import type { IdentityUser } from "../interfaces/identity-repository.js";
import type { ManagedBlogCountRepository, ManagedBlogCountResult } from "../interfaces/managed-blog-count.js";
import type { ProfileOverviewRepository } from "../interfaces/profile-overview-repository.js";

export type ProfileOverviewResult =
  | { kind: "ready"; user: IdentityUser; points: number; blogCount: number }
  | Exclude<ManagedBlogCountResult, { kind: "count" }>;

export class ProfileOverviewService {
  constructor(
    private readonly profiles: ProfileOverviewRepository,
    private readonly blogCounts: ManagedBlogCountRepository,
  ) {}

  async get(user: IdentityUser): Promise<ProfileOverviewResult> {
    const [points, blogCount] = await Promise.all([
      this.profiles.findPointsBalance(user.id),
      this.blogCounts.countForUser(user.id),
    ]);
    if (blogCount.kind !== "count") return blogCount;
    return { kind: "ready", user, points: points ?? 0, blogCount: blogCount.count };
  }
}
