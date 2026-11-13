import type { AgentTool } from "./agent-tool-runtime.js";
import type { AgentToolApprovalService } from "./agent-tool-approval-service.js";
import type { AgentTokenUsage } from "../interfaces/agent-billing-port.js";

export function createPublishPostTool(
  approvals: AgentToolApprovalService,
  userId: number,
  conversationId: number,
  runVersion: number,
  usage: AgentTokenUsage,
  model: string,
  charge: () => Promise<void>,
  onApproval: (eventId: number, approval: unknown) => void,
): AgentTool {
  return {
    definition: () => ({ name: "publish_post", description: "发布博客文章", parameters: {}, readOnly: false }),
    execute: async (_context, args) => {
      await charge();
      const approval = await approvals.requestPublishPost(userId, conversationId, runVersion, args);
      onApproval(approval.eventId, approval.approval);
      return { ok: true, data: approval.approval };
    },
  };
}
