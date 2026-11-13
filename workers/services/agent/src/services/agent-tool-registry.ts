import type { AgentTool } from "./agent-tool-runtime.js";

export interface AgentToolDefinition {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface AgentToolSource {
  tools(): readonly AgentToolDefinition[] | Promise<readonly AgentToolDefinition[]>;
}

/**
 * Owns the tool surface visible to one Agent turn.
 * The registry is intentionally request-scoped because availability depends on
 * the user, conversation, enabled services, skills, and MCP connections.
 */
export class AgentToolRegistry {
  private readonly definitions = new Map<string, AgentToolDefinition>();
  private readonly executors = new Map<string, AgentTool>();

  constructor(definitions: readonly AgentToolDefinition[], executors?: ReadonlyMap<string, AgentTool>) {
    for (const definition of definitions) {
      if (!definition.name || definition.name.length > 64 || this.definitions.has(definition.name)) continue;
      this.definitions.set(definition.name, definition);
    }
    for (const [name, tool] of executors ?? []) {
      if (this.definitions.has(name)) this.executors.set(name, tool);
    }
  }

  static async fromSources(sources: readonly AgentToolSource[]): Promise<AgentToolRegistry> {
    const definitions: AgentToolDefinition[] = [];
    for (const source of sources) {
      try {
        definitions.push(...await source.tools());
      } catch {
        // An unavailable optional source must not remove built-in tools.
      }
    }
    return new AgentToolRegistry(definitions);
  }

  list(): AgentToolDefinition[] {
    return [...this.definitions.values()];
  }

  has(name: string): boolean {
    return this.definitions.has(name);
  }

  get(name: string): AgentToolDefinition | undefined {
    return this.definitions.get(name);
  }

  executor(name: string): AgentTool | undefined {
    return this.executors.get(name);
  }

  registerExecutor(tool: AgentTool): void {
    const name = tool.definition().name;
    if (this.definitions.has(name)) this.executors.set(name, tool);
  }
}
