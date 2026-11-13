import type { AgentBlogAgentPort } from "../interfaces/agent-blog-agent-port.js";
import type { AgentTool } from "./agent-tool-runtime.js";

export function createBlogGenerationTool(
  blogAgent: AgentBlogAgentPort,
  userId: number,
  requestId: string,
  input: string,
  mode: "auto" | "quick" | "full",
  signal: AbortSignal,
  onProgress: (stage: string, message: string) => Promise<void>,
): AgentTool {
  return {
    definition: () => ({
      name: "blog_generate",
      description: "生成博客文章",
      parameters: {},
      readOnly: false,
    }),
    execute: async () => {
      let task = await blogAgent.create(userId, input, requestId, mode);
      if (task.status !== "ready") {
        task = await blogAgent.findReady(userId, requestId)
          ?? await blogAgent.runStream(userId, task.id, signal, onProgress);
      }
      return { ok: true, data: task };
    },
  };
}
