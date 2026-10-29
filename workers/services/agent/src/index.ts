import { createBusinessService, type WorkerExecutionContextLike } from "@dinqor/worker-runtime";
import { handleAgentOssSmokeTestRequest } from "./controllers/agent-oss-smoke-test-controller.js";
import { handleAgentAttachmentSmokeTestRequest } from "./controllers/agent-attachment-smoke-test-controller.js";
import { handleAgentConversationRequest } from "./controllers/agent-conversation-controller.js";
import { handleAgentAttachmentRequest } from "./controllers/agent-attachment-controller.js";
import { handleAgentApprovalRequest } from "./controllers/agent-approval-controller.js";
import { handleAgentRunRequest } from "./controllers/agent-run-controller.js";
import { handleAgentWebSocketRequest } from "./controllers/agent-websocket-controller.js";
import { handleAgentSkillRequest } from "./controllers/agent-skill-controller.js";
import { handleAgentMcpRequest } from "./controllers/agent-mcp-controller.js";
import { CoreAuthMapper } from "./mappers/core-auth-mapper.js";
import { CoreAgentBillingMapper } from "./mappers/core-agent-billing-mapper.js";
import { CoreModelGatewayMapper } from "./mappers/core-model-gateway-mapper.js";
import { CoreAgentImageModelMapper } from "./mappers/core-agent-image-model-mapper.js";
import { OpenAiAgentImageMapper } from "./mappers/openai-agent-image-mapper.js";
import { ContentAgentToolsMapper } from "./mappers/content-agent-tools-mapper.js";
import { ContentAgentPublicationMapper } from "./mappers/content-agent-publication-mapper.js";
import { D1AgentToolApprovalMapper } from "./mappers/d1-agent-tool-approval-mapper.js";
import { D1AgentConversationMapper } from "./mappers/d1-agent-conversation-mapper.js";
import { D1AgentSkillMapper } from "./mappers/d1-agent-skill-mapper.js";
import { D1AgentRunMapper } from "./mappers/d1-agent-run-mapper.js";
import { D1AgentMemoryMapper } from "./mappers/d1-agent-memory-mapper.js";
import { D1AgentAttachmentMapper } from "./mappers/d1-agent-attachment-mapper.js";
import { D1AgentPlanMapper } from "./mappers/d1-agent-plan-mapper.js";
import { D1AgentMcpMapper } from "./mappers/d1-agent-mcp-mapper.js";
import { McpHttpClientMapper } from "./mappers/mcp-http-client-mapper.js";
import { ContentAgentMediaMapper } from "./mappers/content-agent-media-mapper.js";
import { OssAgentAttachmentStorageMapper } from "./mappers/oss-agent-attachment-storage-mapper.js";
import { CoreAgentRecitationMapper } from "./mappers/core-agent-recitation-mapper.js";
import { ContentAgentBlogAgentMapper } from "./mappers/content-agent-blog-agent-mapper.js";
import type { AgentEnvironment } from "./interfaces/agent-environment.js";
import { AgentAuthService } from "./services/agent-auth-service.js";
import { AgentConversationService } from "./services/agent-conversation-service.js";
import { AgentSkillService } from "./services/agent-skill-service.js";
import { AgentRunService } from "./services/agent-run-service.js";
import { AgentToolApprovalService } from "./services/agent-tool-approval-service.js";
import { AgentMemoryService } from "./services/agent-memory-service.js";
import { AgentAttachmentService } from "./services/agent-attachment-service.js";
import { AgentRecitationService } from "./services/agent-recitation-service.js";
import { AgentPlanService } from "./services/agent-plan-service.js";
import { AgentMcpService } from "./services/agent-mcp-service.js";
import { AgentImageService } from "./services/agent-image-service.js";
import { AgentContentToolsService } from "./services/agent-content-tools-service.js";
import { AgentWeatherService } from "./services/agent-weather-service.js";

const agentService = createBusinessService<AgentEnvironment>("agent", async (request, env, context) => {
  const auth = new AgentAuthService(env.JWT_SECRET, new CoreAuthMapper(env));
  const resolveUser = () => auth.currentUserId(request);
  const contentMedia = new ContentAgentMediaMapper(env);
  const billing = new CoreAgentBillingMapper(env);
  const attachmentService = new AgentAttachmentService(
    new D1AgentAttachmentMapper(env.DB),
    new OssAgentAttachmentStorageMapper(env),
    contentMedia,
  );
  const turnRepository = new D1AgentRunMapper(env.DB);
  const mcpService = new AgentMcpService(new D1AgentMcpMapper(env.DB), new McpHttpClientMapper(env), env);
  const contentTools = new AgentContentToolsService(new ContentAgentToolsMapper(env));
  const weather = new AgentWeatherService(env);
  const approvalService = new AgentToolApprovalService(
    new D1AgentToolApprovalMapper(env.DB),
    turnRepository,
    new ContentAgentPublicationMapper(env),
    mcpService,
    contentTools,
    weather,
  );
  const runService = new AgentRunService(
    turnRepository,
    new CoreModelGatewayMapper(env),
    billing,
    approvalService,
    new D1AgentSkillMapper(env.DB),
    new AgentMemoryService(new D1AgentMemoryMapper(env.DB)),
    attachmentService,
    new AgentRecitationService(new CoreAgentRecitationMapper(env)),
    new ContentAgentBlogAgentMapper(env),
    new AgentPlanService(new D1AgentPlanMapper(env.DB)),
    mcpService,
    new AgentImageService(new CoreAgentImageModelMapper(env), new OpenAiAgentImageMapper(), contentMedia, billing),
    contentTools,
    weather,
  );
  const approvalResponse = await handleAgentApprovalRequest(request, approvalService, resolveUser);
  if (approvalResponse) return approvalResponse;
  const attachmentResponse = await handleAgentAttachmentRequest(request, attachmentService, resolveUser);
  if (attachmentResponse) return attachmentResponse;
  const webSocketResponse = await handleAgentWebSocketRequest(request, runService, resolveUser, context);
  if (webSocketResponse) return webSocketResponse;
  const mcpResponse = await handleAgentMcpRequest(request, mcpService, auth);
  if (mcpResponse) return mcpResponse;
  const runResponse = await handleAgentRunRequest(request, runService, resolveUser, context);
  if (runResponse) return runResponse;

  const skillService = new AgentSkillService(new D1AgentSkillMapper(env.DB));
  const skillResponse = await handleAgentSkillRequest(request, skillService, resolveUser);
  if (skillResponse) return skillResponse;

  const conversationService = new AgentConversationService(new D1AgentConversationMapper(env.DB), attachmentService);
  return handleAgentConversationRequest(request, conversationService, resolveUser);
});

export default {
  ...agentService,
  async fetch(request: Request, env: AgentEnvironment, context: WorkerExecutionContextLike): Promise<Response> {
    const attachmentSmokeTest = await handleAgentAttachmentSmokeTestRequest(request, env);
    if (attachmentSmokeTest) return attachmentSmokeTest;
    const ossSmokeTest = await handleAgentOssSmokeTestRequest(request, env);
    if (ossSmokeTest) return ossSmokeTest;
    return agentService.fetch(request, env, context);
  },
};
