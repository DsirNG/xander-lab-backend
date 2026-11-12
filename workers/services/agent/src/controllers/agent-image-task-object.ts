import type { AgentEnvironment } from "../interfaces/agent-environment.js";
import type { AgentImageTask, AgentImageTaskStorage } from "../interfaces/agent-image-task.js";
import { D1AgentRunMapper } from "../mappers/d1-agent-run-mapper.js";
import { CoreAgentImageModelMapper } from "../mappers/core-agent-image-model-mapper.js";
import { OpenAiAgentImageMapper } from "../mappers/openai-agent-image-mapper.js";
import { ContentAgentMediaMapper } from "../mappers/content-agent-media-mapper.js";
import { CoreAgentBillingMapper } from "../mappers/core-agent-billing-mapper.js";
import { AgentImageService } from "../services/agent-image-service.js";
import { AgentImageTaskService } from "../services/agent-image-task-service.js";

export class AgentImageTaskObject {
  private readonly service: AgentImageTaskService;

  constructor(private readonly state: {
    storage: AgentImageTaskStorage;
    blockConcurrencyWhile<T>(callback: () => Promise<T>): Promise<T>;
  }, env: AgentEnvironment) {
    this.service = new AgentImageTaskService(state.storage, new D1AgentRunMapper(env.DB),
      new AgentImageService(new CoreAgentImageModelMapper(env), new OpenAiAgentImageMapper(),
        new ContentAgentMediaMapper(env), new CoreAgentBillingMapper(env)));
  }

  async fetch(request: Request): Promise<Response> {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/start") return new Response(null, { status: 404 });
    const task = await request.json() as AgentImageTask;
    if (![task.userId, task.conversationId, task.runVersion].every((n) => Number.isSafeInteger(n) && n > 0)) {
      return new Response(null, { status: 400 });
    }
    await this.state.blockConcurrencyWhile(() => this.service.start(task));
    return new Response(null, { status: 202 });
  }

  alarm(): Promise<void> { return this.service.alarm(); }
}
