import type { AgentImageTask, AgentImageTaskNamespace, AgentImageTaskPort } from "../interfaces/agent-image-task.js";

/** One durable alarm owns each image invocation, independently of subscriber connections. */
export class DurableAgentImageTaskMapper implements AgentImageTaskPort {
  constructor(private readonly namespace: AgentImageTaskNamespace) {}

  async start(task: AgentImageTask): Promise<void> {
    const id = this.namespace.idFromName(`image-${task.conversationId}-${task.runVersion}`);
    const response = await this.namespace.get(id).fetch(new Request("https://image-task/start", {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(task),
    }));
    if (!response.ok) throw new Error("图片任务启动失败");
  }
}
