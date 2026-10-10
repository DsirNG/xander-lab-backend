import type { BlogPlanRun } from "./blog-plan-repository.js";

export interface BlogPlanExecutionPort {
  trigger(userId: number, planId: number): Promise<BlogPlanRun>;
  executeManual(userId: number, planId: number, runId: number): Promise<void>;
  processDue(now: number): Promise<void>;
}
