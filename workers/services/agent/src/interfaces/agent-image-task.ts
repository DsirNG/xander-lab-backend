export interface AgentImageTask {
  userId: number;
  conversationId: number;
  runVersion: number;
  args: unknown;
}

export interface AgentImageTaskPort {
  start(task: AgentImageTask): Promise<void>;
}

export interface AgentImageTaskNamespace {
  idFromName(name: string): unknown;
  get(id: unknown): { fetch(request: Request): Promise<Response> };
}

export interface AgentImageTaskStorage {
  get<T>(key: string): Promise<T | undefined>;
  put<T>(key: string, value: T): Promise<void>;
  setAlarm(time: number): Promise<void>;
  deleteAlarm(): Promise<void>;
}
