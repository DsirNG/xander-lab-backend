export type BlogPlanStatus = "ACTIVE" | "RUNNING" | "PAUSED" | "CANCELLED" | "FINISHED" | "FAILED";
export type BlogPlanScheduleType = "DAILY" | "WEEKLY" | "ONCE";

export interface BlogPlan {
  id: number;
  userId: number;
  clientRequestId: string | null;
  planSeriesId?: number | null;
  seriesDay?: number | null;
  seriesPublishMode?: "DRAFT" | "REVIEW" | "AUTO" | null;
  topic: string;
  audience: string;
  tone: string;
  scheduleType: BlogPlanScheduleType;
  scheduledDate: string | null;
  aiOption: "DEEP" | "PRACTICAL" | "NEWS" | "OPINION";
  knowledgeMaterialId: number | null;
  autoPublish: boolean;
  timezone: string;
  triggerTime: string;
  triggerTimes: string[];
  topics: string[];
  nextTopicIndex: number;
  syncCsdn: boolean;
  syncJuejin: boolean;
  runOnce: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  processingToken?: string | null;
  claimedAt?: string | null;
  status: BlogPlanStatus;
  errorMessage: string | null;
  deletedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BlogPlanRun {
  id: number;
  planId: number;
  scheduledAt: string;
  triggerType: string;
  status: string;
  stageError: string | null;
  errorMessage: string | null;
  agentTaskId: number | null;
  localPostId: number | null;
  reviewPass: boolean | null;
  reviewReason: string | null;
  csdnStatus: string | null;
  csdnErrorCode: string | null;
  csdnErrorMessage: string | null;
  csdnExternalId: string | null;
  csdnUrl: string | null;
  juejinStatus: string | null;
  juejinErrorCode: string | null;
  juejinErrorMessage: string | null;
  juejinExternalId: string | null;
  juejinUrl: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

export interface BlogPlanRepository {
  findById(userId: number, planId: number): Promise<BlogPlan | null>;
  findByRequestId(userId: number, requestId: string): Promise<BlogPlan | null>;
  list(userId: number, limit: number, offset: number): Promise<BlogPlan[]>;
  count(userId: number): Promise<number>;
  countActive(userId: number): Promise<number>;
  countCreatedSince(userId: number, since: string): Promise<number>;
  create(plan: Omit<BlogPlan, "id" | "createdAt" | "updatedAt">): Promise<number>;
  createMany(plans: Array<Omit<BlogPlan, "id" | "createdAt" | "updatedAt">>): Promise<number[]>;
  claimDue(now: string, limit: number, tokenPrefix: string): Promise<BlogPlan[]>;
  listStaleClaims?(cutoff: string, limit: number): Promise<BlogPlan[]>;
  hasSucceededRun?(planId: number, processingToken: string): Promise<boolean>;
  recoverStalePlan?(input: {
    planId: number; token: string; cutoff: string; status: BlogPlanStatus;
    nextRunAt: string | null; lastRunAt: string; errorMessage: string | null; updatedAt: string;
  }): Promise<boolean>;
  failStaleManualRuns?(cutoff: string, updatedAt: string, limit: number): Promise<number>;
  createScheduledRun(plan: BlogPlan, scheduledAt: string, token: string): Promise<number | null>;
  createManualRun(userId: number, planId: number, scheduledAt: string, token: string): Promise<number | null>;
  consumeNextTopic(planId: number): Promise<string | null>;
  updateRun(planId: number, runId: number, patch: Partial<BlogPlanRun>): Promise<boolean>;
  finishScheduledPlan(input: {
    planId: number;
    token: string;
    status: BlogPlanStatus;
    nextRunAt: string | null;
    lastRunAt: string;
    errorMessage: string | null;
    updatedAt: string;
  }): Promise<boolean>;
  update(plan: BlogPlan, expectedStatus: BlogPlanStatus): Promise<boolean>;
  setStatus(userId: number, planId: number, from: BlogPlanStatus[], status: BlogPlanStatus, values?: { nextRunAt?: string | null; errorMessage?: string | null }): Promise<boolean>;
  softDelete(userId: number, planId: number, deletedAt: string): Promise<boolean>;
  listRuns(planId: number, limit: number, offset: number): Promise<BlogPlanRun[]>;
  countRuns(planId: number): Promise<number>;
  findRun(planId: number, runId: number): Promise<BlogPlanRun | null>;
  refreshSeriesStatus?(seriesId: number): Promise<void>;
}
