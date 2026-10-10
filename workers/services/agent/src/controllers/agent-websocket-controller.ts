import { apiError } from "@dinqor/worker-runtime";
import type { WorkerExecutionContextLike } from "@dinqor/worker-runtime";
import type { AgentRunService } from "../services/agent-run-service.js";

interface SocketLike {
  accept(): void;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  addEventListener(type: "close" | "error", listener: () => void): void;
}

interface SocketPairLike { client: SocketLike; server: SocketLike }
type UserResolver = () => Promise<number | Response>;
type PairFactory = () => SocketPairLike | null;
type UpgradeFactory = (client: SocketLike) => Response;

const POLL_INTERVAL_MS = 500;
const HEARTBEAT_INTERVAL_MS = 20_000;

function positiveInteger(value: string | null): number | null {
  if (!value || !/^[1-9]\d{0,17}$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function eventData(value: string): unknown {
  try { return JSON.parse(value) as unknown; }
  catch { return value; }
}

function defaultPairFactory(): SocketPairLike | null {
  const ctor = (globalThis as unknown as {
    WebSocketPair?: new () => { 0: SocketLike; 1: SocketLike };
  }).WebSocketPair;
  if (!ctor) return null;
  const pair = new ctor();
  return { client: pair[0], server: pair[1] };
}

function defaultUpgrade(client: SocketLike): Response {
  return new Response(null, {
    status: 101,
    webSocket: client as unknown as WebSocket,
  } as ResponseInit);
}

export async function handleAgentWebSocketRequest(
  request: Request,
  service: AgentRunService,
  resolveUser: UserResolver,
  context?: WorkerExecutionContextLike,
  pairFactory: PairFactory = defaultPairFactory,
  upgradeFactory: UpgradeFactory = defaultUpgrade,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== "/ws/agent") return null;
  if (request.method !== "GET") return null;
  if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
    return new Response(null, { status: 426, headers: { upgrade: "websocket" } });
  }

  const userId = await resolveUser();
  if (userId instanceof Response) return userId;
  const conversationId = positiveInteger(url.searchParams.get("conversationId"));
  const runVersion = positiveInteger(url.searchParams.get("runVersion"));
  if (!conversationId || !runVersion) return apiError(400, "会话订阅参数无效");
  const conversation = await service.getConversation(userId, conversationId);
  if (!conversation) return apiError(404, "会话不存在");

  const pair = pairFactory();
  if (!pair) return apiError(501, "当前 Worker runtime 不支持 WebSocket");
  pair.server.accept();
  let open = true;
  const cursorText = url.searchParams.get("afterEventId") ?? "0";
  let cursor = /^\d+$/.test(cursorText) && Number.isSafeInteger(Number(cursorText)) ? Number(cursorText) : 0;
  let lastHeartbeatAt = Date.now();
  pair.server.addEventListener("message", (event) => {
    if (!open || typeof event.data !== "string") return;
    try {
      const value: unknown = JSON.parse(event.data);
      if (isObject(value) && value.event === "ping") pair.server.send(JSON.stringify({ event: "pong" }));
    } catch {
      // Ignore malformed frames, matching the Java WebSocket contract.
    }
  });
  const close = (code = 1000, reason = "") => {
    if (!open) return;
    open = false;
    try { pair.server.close(code, reason); } catch { /* The peer may already be gone. */ }
  };
  pair.server.addEventListener("close", () => { open = false; });
  pair.server.addEventListener("error", () => { open = false; });

  const stream = (async () => {
    try {
      while (open) {
        const events = await service.listEventsAfter(conversationId, runVersion, cursor);
        for (const event of events) {
          if (!open) break;
          const frame = {
            ...(event.id === null ? {} : { id: event.id }),
            event: event.eventType,
            data: eventData(event.dataContent),
          };
          pair.server.send(JSON.stringify(frame));
          if (event.id !== null) cursor = Math.max(cursor, event.id);
          if (event.eventType === "complete" || event.eventType === "error") {
            close(1000, "turn complete");
            break;
          }
        }
        if (!open) break;
        const current = await service.getConversation(userId, conversationId);
        if (!current || current.runVersion !== runVersion) {
          close(1000, "turn superseded");
          break;
        }
        if (Date.now() - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
          pair.server.send(JSON.stringify({ event: "ping" }));
          lastHeartbeatAt = Date.now();
        }
        await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
      }
    } catch {
      close(1011, "event stream unavailable");
    }
  })();
  context?.waitUntil(stream);
  return upgradeFactory(pair.client);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
