export interface BlogAgentTask {
  id: number;
  userId: number;
  clientRequestId: string | null;
  input: string;
  generationMode: "quick" | "full";
  inputType: string;
  audience: string;
  tone: string;
  status: string;
  stage: string;
  title: string | null;
  summary: string | null;
  content: string | null;
  contentObjectKey?: string | null;
  outline: string | null;
  contentBoundary: string;
  knowledgeGraphJson: string;
  categoryId: string;
  tagsJson: string;
  review: string | null;
  illustrationStatus: string;
  illustrationError: string | null;
  publishedPostId: number | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BlogAgentMessage {
  id: number; taskId: number; role: string; kind: string; stage: string | null;
  content: string; createdAt: string;
}

export interface BlogAgentSource {
  id: number; taskId: number; title: string; url: string; publisher: string | null;
  excerpt: string | null; reliability: string | null; retrievedAt: string;
}

export interface BlogAgentVersion {
  id: number; taskId: number; versionNo: number; title: string; summary: string;
  content: string; contentObjectKey?: string | null; changeNote: string | null; createdAt: string;
}

export interface BlogAgentEvent {
  id: number; taskId: number; eventType: string; dataContent: string; createdAt: string;
}

export interface BlogAgentTaskView {
  task: BlogAgentTask;
  tags: string[];
  contentBoundary: Record<string, unknown>;
  knowledgeGraph: Record<string, unknown>;
  sources: BlogAgentSource[];
  versions: BlogAgentVersion[];
  illustrations: [];
  messages: BlogAgentMessage[];
}

export interface BlogAgentSessionView {
  id: number; title: string; input: string; status: string; stage: string;
  createdAt: string; updatedAt: string;
}

export interface BlogAgentTaskRepository {
  create(userId: number, input: string, clientRequestId: string | null, now: string, generationMode?: "quick" | "full"): Promise<number>;
  findById(userId: number, taskId: number): Promise<BlogAgentTask | null>;
  findByRequestId(userId: number, clientRequestId: string): Promise<BlogAgentTask | null>;
  list(userId: number, limit: number): Promise<BlogAgentTask[]>;
  hydrate(userId: number, taskId: number): Promise<BlogAgentTaskView | null>;
  claim(userId: number, taskId: number, mode: "initial" | "revision", now: string): Promise<boolean>;
  updateStage(userId: number, taskId: number, stage: string, message: string, now: string): Promise<boolean>;
  fail(userId: number, taskId: number, message: string, stage: string, revision: boolean, now: string): Promise<void>;
  complete(userId: number, taskId: number, article: Record<string, unknown>, review: Record<string, unknown>, contentObjectKey: string | null, now: string): Promise<boolean>;
  publish(userId: number, taskId: number, postId: number, now: string): Promise<void>;
  appendEvent(taskId: number, eventType: string, data: string, now: string): Promise<number>;
  listEventsAfter(taskId: number, cursor: number, limit: number): Promise<BlogAgentEvent[]>;
  recoverStale(userId: number, taskId: number, cutoff: string, now: string): Promise<void>;
}
