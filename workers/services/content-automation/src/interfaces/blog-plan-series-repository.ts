import type { BlogPlan } from "./blog-plan-repository.js";

export type BlogPlanSeriesStatus =
  | "DRAFT"
  | "ACTIVATING"
  | "ACTIVE"
  | "PAUSED"
  | "CANCELLED"
  | "FINISHED"
  | "FAILED";
export type BlogPlanSeriesMode = "DRAFT" | "REVIEW" | "AUTO";

export interface BlogPlanSeries {
  id: number;
  userId: number;
  clientRequestId?: string | null;
  title: string;
  topic: string;
  topics: string[];
  audience: string;
  tone: string;
  aiOption: BlogPlan["aiOption"];
  startDate: string;
  triggerTime: string;
  timezone: string;
  platforms: Array<"local" | "csdn" | "juejin">;
  publishMode: BlogPlanSeriesMode;
  status: BlogPlanSeriesStatus;
  totalItems?: number;
  finishedItems?: number;
  failedItems?: number;
  createdAt: string;
  updatedAt: string;
}

export interface BlogPlanSeriesRepository {
  create(
    input: Omit<BlogPlanSeries, "id" | "createdAt" | "updatedAt">,
  ): Promise<number>;
  findByClientRequestId(
    userId: number,
    clientRequestId: string,
  ): Promise<BlogPlanSeries | null>;
  find(userId: number, seriesId: number): Promise<BlogPlanSeries | null>;
  list(userId: number): Promise<BlogPlanSeries[]>;
  listPlans(userId: number, seriesId: number): Promise<BlogPlan[]>;
  updateDraft(input: BlogPlanSeries): Promise<boolean>;
  activate(
    userId: number,
    seriesId: number,
    plans: Array<Omit<BlogPlan, "id" | "createdAt" | "updatedAt">>,
  ): Promise<boolean>;
  control(
    userId: number,
    seriesId: number,
    action: "PAUSE" | "RESUME" | "CANCEL" | "RETRY_FAILED",
    retryAt?: string,
  ): Promise<boolean>;
}
