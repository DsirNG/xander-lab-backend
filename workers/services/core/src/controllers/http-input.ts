import { apiError } from "@dinqor/worker-runtime";

export async function readJsonObject(
  request: Request,
  maxBytes: number,
): Promise<Record<string, unknown> | Response> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && Number(declaredLength) > maxBytes) return apiError(413, "请求内容过大");
  if (!request.body) return apiError(400, "请求内容格式不正确");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) {
        await reader.cancel();
        return apiError(413, "请求内容过大");
      }
      chunks.push(value);
    }
  } catch {
    return apiError(400, "请求内容格式不正确");
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    const body: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return body && typeof body === "object" && !Array.isArray(body)
      ? body as Record<string, unknown>
      : apiError(400, "请求内容格式不正确");
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}
