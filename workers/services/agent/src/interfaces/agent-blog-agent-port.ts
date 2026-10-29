export interface AgentBlogTaskSummary {
  id: number;
  title: string;
  summary: string;
  publishedPostId: number | null;
  status: string;
  generationMode: "quick" | "full";
}

export interface AgentBlogAgentPort {
  create(userId: number, input: string, clientRequestId: string, mode?: "auto" | "quick" | "full"): Promise<AgentBlogTaskSummary>;
  findReady(userId: number, clientRequestId: string): Promise<AgentBlogTaskSummary | null>;
  runStream(
    userId: number,
    taskId: number,
    signal: AbortSignal,
    onStage: (stage: string, message: string) => Promise<void>,
  ): Promise<AgentBlogTaskSummary>;
}
