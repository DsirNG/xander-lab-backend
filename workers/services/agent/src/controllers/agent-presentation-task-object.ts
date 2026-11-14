import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";
import type { PresentationStorage } from "../interfaces/agent-presentation.js";
import { D1AgentAttachmentMapper } from "../mappers/d1-agent-attachment-mapper.js";
import { OssAgentAttachmentStorageMapper } from "../mappers/oss-agent-attachment-storage-mapper.js";
import { HttpPresentationRendererMapper } from "../mappers/http-presentation-renderer-mapper.js";
import { AgentAttachmentService } from "../services/agent-attachment-service.js";
import { AgentPresentationTaskService } from "../services/agent-presentation-task-service.js";
import { PresentationError } from "../services/agent-presentation-validation.js";

export class AgentPresentationTaskObject {
  private readonly tasks: AgentPresentationTaskService;
  constructor(state: { storage: PresentationStorage; blockConcurrencyWhile<T>(callback: () => Promise<T>): Promise<T> }, env: AgentEnvironment) {
    this.tasks = new AgentPresentationTaskService(state.storage,
      new HttpPresentationRendererMapper(env.PPT_RENDERER_URL ?? "", env.PPT_RENDERER_TOKEN ?? ""),
      // Generated previews share the Agent artifact lifecycle and do not create library assets.
      new AgentAttachmentService(new D1AgentAttachmentMapper(env.DB), new OssAgentAttachmentStorageMapper(env)),
      callback => state.blockConcurrencyWhile(callback));
  }
  async fetch(request: Request): Promise<Response> {
    if (request.method !== "POST") return apiError(405, "Method not allowed");
    try {
      const input = await request.json() as Record<string, unknown>;
      const userId = input.userId;
      if (typeof userId !== "number" || !Number.isSafeInteger(userId) || userId <= 0) return apiError(401, "身份无效");
      const action = new URL(request.url).pathname.slice(1);
      if (action === "start") {
        if (typeof input.id !== "string" || !/^[a-f0-9]{32}$/.test(input.id)
          || typeof input.conversationId !== "number" || !Number.isSafeInteger(input.conversationId) || input.conversationId <= 0) return apiError(400, "PPT 参数无效");
        await this.tasks.start({ id: input.id, userId, conversationId: input.conversationId, deck: input.deck });
      } else if (action !== "get") await this.tasks.mutate(userId, action, input);
      const version = action === "get" && input.version !== undefined ? Number(input.version) : undefined;
      return jsonResponse({ code: 200, message: "success", data: await this.tasks.view(userId, version) });
    } catch (error) {
      if (error instanceof PresentationError) return apiError(error.status, error.message);
      if (error instanceof SyntaxError) return apiError(400, "PPT 请求格式无效");
      return apiError(503, "PPT 服务暂不可用");
    }
  }
  alarm() { return this.tasks.alarm(); }
}
