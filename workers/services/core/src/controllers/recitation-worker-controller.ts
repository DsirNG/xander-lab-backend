import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { RecitationAsrWorkerService } from "../services/recitation-asr-worker-service.js";

/** Internal-only manual hook for local verification and operational replay. */
export async function handleRecitationWorkerRequest(
  request: Request,
  worker: RecitationAsrWorkerService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== "/internal/recitation/asr/process") return null;
  if (request.method !== "POST") return apiError(405, "请求方法不支持");
  try {
    await worker.process();
    return jsonResponse({ code: 200, message: "success", data: { processed: true } });
  } catch {
    return apiError(503, "背诵任务处理暂不可用");
  }
}
