export interface AgentImageModelEnvironment {
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: { fetch(request: Request): Promise<Response> };
  AGENT_IMAGE_MODEL_BASE_URL?: string;
  AGENT_IMAGE_MODEL_API_KEY?: string;
  AGENT_IMAGE_MODEL_NAME?: string;
  AGENT_IMAGE_MODEL_API_STYLE?: string;
  AGENT_IMAGE_MODEL_SIZE?: string;
}
