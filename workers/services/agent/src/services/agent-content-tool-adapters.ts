import type { AgentContentToolsService } from "./agent-content-tools-service.js";
import type { AgentTool } from "./agent-tool-runtime.js";
import type { AgentToolDefinition } from "./agent-tool-registry.js";

/** Creates user-scoped AgentTool adapters for content read operations. */
export function createContentQueryTools(
  service: AgentContentToolsService,
  userId: number,
  definitions: readonly AgentToolDefinition[],
): Map<string, AgentTool> {
  return new Map(definitions.map((definition) => [definition.name, {
    definition: () => ({ ...definition, readOnly: true }),
    execute: async (_context, args) => {
      const result = await service.execute(userId, definition.name, args);
      return { ok: true, data: result.data, answer: result.answer };
    },
  } satisfies AgentTool]));
}

export function createContentMutationTool(
  service: AgentContentToolsService,
  userId: number,
  toolName: string,
): AgentTool {
  return {
    definition: () => ({ name: toolName, description: `执行内容操作：${toolName}`, parameters: {}, readOnly: false }),
    execute: async (context, args) => ({
      ok: true,
      data: await service.execute(userId, toolName, args),
      links: [{ invocationId: context.invocationId }],
    }),
  };
}
