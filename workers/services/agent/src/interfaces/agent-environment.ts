import type { D1Binding, ServiceEnvironment } from "@dinqor/worker-runtime";
import type { AgentModelEnvironment } from "./agent-model-gateway.js";
import type { AgentImageModelEnvironment } from "./agent-image-model-environment.js";
import type { ContentAgentPublicationEnvironment } from "../mappers/content-agent-publication-mapper.js";

export interface AgentEnvironment extends ServiceEnvironment, AgentModelEnvironment, AgentImageModelEnvironment, ContentAgentPublicationEnvironment {
  DB: D1Binding;
  JWT_SECRET?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
  AGENT_OSS_OBJECT_PREFIX?: string;
  AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY?: string;
  AGENT_MCP_CLIENT_ENABLED?: string;
  CONTENT_AUTOMATION_SERVICE?: { fetch(request: Request): Promise<Response> };
}
