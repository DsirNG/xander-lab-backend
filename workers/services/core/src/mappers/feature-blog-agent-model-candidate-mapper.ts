import type { BlogAgentModelCandidatePort } from "../interfaces/blog-agent-model.js";
import { resolveFeatureModelGateway, type ModelGatewayEnvironment } from "../services/model-gateway-service.js";

export class FeatureBlogAgentModelCandidateMapper implements BlogAgentModelCandidatePort {
  constructor(private readonly env: ModelGatewayEnvironment) {}

  async candidates() {
    const gateway = await resolveFeatureModelGateway(this.env, "blog_agent");
    return gateway.candidates.filter((candidate) =>
      candidate.apiStyle === "RESPONSES" || candidate.apiStyle === "CHAT_COMPLETIONS");
  }
}
