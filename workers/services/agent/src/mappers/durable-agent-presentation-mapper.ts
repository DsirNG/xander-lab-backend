import type { PresentationNamespace, PresentationPort, PresentationView } from "../interfaces/agent-presentation.js";
import { parsePresentationDeck, PresentationError } from "../services/agent-presentation-validation.js";
import { normalizePresentationId } from "../services/agent-presentation-id.js";

export class DurableAgentPresentationMapper implements PresentationPort {
  constructor(private readonly namespace: PresentationNamespace) {}

  async create(userId: number, conversationId: number, key: string, input: unknown): Promise<PresentationView> {
    const deck = parsePresentationDeck(input);
    const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${userId}:${conversationId}:${key}`));
    const id = Array.from(new Uint8Array(hash).slice(0, 16), n => n.toString(16).padStart(2, "0")).join("");
    return this.call(userId, id, "start", { conversationId, deck });
  }

  action(userId: number, id: string, action: string, input: Record<string, unknown> = {}) {
    const normalized = normalizePresentationId(id);
    if (!normalized) throw new PresentationError(400, "PPT 标识无效");
    return this.call(userId, normalized, action, input);
  }

  private async call(userId: number, id: string, action: string, input: Record<string, unknown>): Promise<PresentationView> {
    const stub = this.namespace.get(this.namespace.idFromName(`presentation:${id}`));
    const response = await stub.fetch(new Request(`https://presentation/${action}`, { method: "POST",
      headers: { "content-type": "application/json" }, body: JSON.stringify({ ...input, id, userId }) }));
    const body = await response.json() as { message?: string; data: PresentationView };
    if (!response.ok) throw new PresentationError(response.status, body.message ?? "PPT 服务暂不可用");
    return body.data;
  }
}
