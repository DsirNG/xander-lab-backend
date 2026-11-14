import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { AgentAuthService } from "../services/agent-auth-service.js";
import { AgentWebSettingsError, type AgentWebSettingsService } from "../services/agent-web-settings-service.js";

const success = (data: unknown) => jsonResponse({ code: 200, message: "success", data });

async function readSettings(request: Request): Promise<unknown> {
  if (!request.body) throw new AgentWebSettingsError(400, "请求内容不能为空");
  const reader = request.body.getReader();
  let length = 0;
  let content = "";
  const decoder = new TextDecoder();
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      length += chunk.value.byteLength;
      if (length > 4096) throw new AgentWebSettingsError(413, "请求内容过大");
      content += decoder.decode(chunk.value, { stream: true });
    }
    return JSON.parse(content + decoder.decode()) as unknown;
  } catch (error) {
    if (error instanceof AgentWebSettingsError) throw error;
    throw new AgentWebSettingsError(400, "请求内容格式不正确");
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

/** Admin-only write-only search credential management. */
export async function handleAgentWebSettingsRequest(request: Request, service: AgentWebSettingsService, auth: AgentAuthService): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (path !== "/api/admin/agent-web-search" && path !== "/api/admin/agent-web-search/test") return null;
  const admin = await auth.currentAdminUserId(request);
  if (admin instanceof Response) return admin;
  try {
    if (path.endsWith("/test")) return request.method === "POST" ? success(await service.probe(request.signal)) : apiError(405, "请求方法不支持");
    if (request.method === "GET") return success(await service.view());
    if (request.method === "PUT") return success(await service.save(await readSettings(request), admin));
    return apiError(405, "请求方法不支持");
  } catch (error) {
    if (error instanceof AgentWebSettingsError) return apiError(error.status, error.message);
    return apiError(503, "搜索配置服务暂不可用");
  }
}
