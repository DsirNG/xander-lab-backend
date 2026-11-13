import type { AgentMcpToolDefinition } from "../interfaces/agent-mcp-tool-port.js";
import type { AgentToolApprovalService } from "./agent-tool-approval-service.js";
import type { AgentTool } from "./agent-tool-runtime.js";

export function createMcpApprovalTool(
  definition: AgentMcpToolDefinition,
  approvals: AgentToolApprovalService,
  userId: number,
  conversationId: number,
  runVersion: number,
  charge: () => Promise<void>,
  onApproval: (eventId: number, approval: unknown) => void,
): AgentTool {
  return {
    definition: () => ({ name: definition.fullName, description: definition.description, parameters: definition.inputSchema, readOnly: false }),
    execute: async (_context, args) => {
      await charge();
      const approval = await approvals.requestRemoteTool(userId, conversationId, runVersion, definition, args);
      onApproval(approval.eventId, approval.approval);
      return { ok: true, data: approval.approval };
    },
  };
}
