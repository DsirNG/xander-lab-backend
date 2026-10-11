import type {
  AgentAttachmentReference,
  AgentAttachmentRepository,
  AgentAttachmentStorage,
} from "../interfaces/agent-attachment.js";
import type { AgentMediaAssetPort } from "../interfaces/agent-media-asset-port.js";

const MAX_BYTES = 20 * 1024 * 1024;
const MAX_NAME_LENGTH = 255;
const ALLOWED_EXTENSIONS = new Set([
  "png", "jpg", "jpeg", "webp", "gif", "pdf", "txt", "md", "json", "html", "xml", "doc", "docx",
  "rtf", "odt", "ppt", "pptx", "csv", "xls", "xlsx", "tsv", "java", "js", "jsx", "ts", "tsx", "py", "css",
]);

export class AgentAttachmentError extends Error {
  constructor(message: string, readonly status = 400) {
    super(message);
    this.name = "AgentAttachmentError";
  }
}

function positiveId(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : null;
}

export class AgentAttachmentService {
  constructor(
    private readonly repository: AgentAttachmentRepository,
    private readonly storage: AgentAttachmentStorage,
    private readonly mediaAssets?: AgentMediaAssetPort,
  ) {}

  async upload(userId: number, file: { name: string; type: string; size: number; bytes: Uint8Array }): Promise<AgentAttachmentReference> {
    if (!Number.isSafeInteger(userId) || userId <= 0) throw new AgentAttachmentError("用户身份无效", 401);
    if (file.size <= 0 || file.bytes.byteLength !== file.size) throw new AgentAttachmentError("附件不能为空");
    if (file.size > MAX_BYTES) throw new AgentAttachmentError("单个附件不能超过20MB", 413);
    const name = file.name.trim().slice(0, MAX_NAME_LENGTH) || "attachment";
    const extension = name.toLowerCase().split(".").at(-1) ?? "";
    if (!name.includes(".") || !ALLOWED_EXTENSIONS.has(extension)) throw new AgentAttachmentError("暂不支持该附件格式");
    const contentType = file.type.trim().slice(0, 120) || "application/octet-stream";
    const objectKey = `${userId}/${crypto.randomUUID()}.${extension}`;
    let stage = "oss-write";
    let objectWritten = false;
    let mediaAssetId: number | null = null;
    try {
      await this.storage.write(objectKey, file.bytes, contentType);
      objectWritten = true;
      if (contentType.toLowerCase().startsWith("image/") && this.mediaAssets) {
        stage = "media-asset";
        mediaAssetId = (await this.mediaAssets.uploadImage(userId, name, contentType, file.bytes)).id;
      }
      stage = "metadata";
      const row = await this.repository.create({ userId, objectKey, name, contentType, size: file.size, mediaAssetId });
      if (!row) throw new Error("Agent attachment metadata insert failed");
      stage = "signed-read-url";
      return await this.reference(row);
    } catch (error) {
      console.error("Agent attachment upload failed", {
        stage,
        error: error instanceof Error ? error.name : "UnknownError",
      });
      if (mediaAssetId !== null && this.mediaAssets) {
        try { await this.mediaAssets.deleteAsset(userId, mediaAssetId); } catch { /* Keep the original failure. */ }
      }
      if (objectWritten) {
        try { await this.storage.delete(objectKey); } catch { /* Keep the original failure. */ }
      }
      throw error;
    }
  }

  async resolveOwned(userId: number, references: unknown[]): Promise<AgentAttachmentReference[]> {
    if (references.length > 5) throw new AgentAttachmentError("每条消息最多支持5个附件");
    const ids: number[] = [];
    for (const reference of references) {
      const value = reference && typeof reference === "object" && !Array.isArray(reference)
        ? (reference as Record<string, unknown>).id : reference;
      const id = positiveId(value);
      if (!id) throw new AgentAttachmentError("附件信息无效");
      if (!ids.includes(id)) ids.push(id);
    }
    if (ids.length === 0) return [];
    const rows = await this.repository.findOwnedMany(userId, ids);
    const byId = new Map(rows.map((row) => [row.id, row]));
    if (ids.some((id) => !byId.has(id))) throw new AgentAttachmentError("附件不存在或不属于当前用户", 404);
    return Promise.all(ids.map((id) => this.reference(byId.get(id)!)));
  }

  async refreshStored(userId: number, value: unknown): Promise<AgentAttachmentReference[]> {
    return (await this.refreshStoredMany(userId, [value]))[0] ?? [];
  }

  async refreshStoredMany(userId: number, values: unknown[]): Promise<AgentAttachmentReference[][]> {
    const idsByValue = values.map((value) => Array.isArray(value)
      ? value.map((item) => item && typeof item === "object" && !Array.isArray(item)
        ? positiveId((item as Record<string, unknown>).id) : null).filter((id): id is number => id !== null).slice(0, 5)
      : []);
    const ids = [...new Set(idsByValue.flat())].slice(0, 500);
    if (ids.length === 0) return values.map(() => []);
    const rows = await this.repository.findOwnedMany(userId, ids);
    const byId = new Map(rows.map((row) => [row.id, row]));
    const references = new Map<number, AgentAttachmentReference>();
    await Promise.all(ids.map(async (id) => {
      const row = byId.get(id);
      if (row) references.set(id, await this.reference(row));
    }));
    return idsByValue.map((valueIds) => valueIds.flatMap((id) => {
      const reference = references.get(id);
      return reference ? [reference] : [];
    }));
  }

  private async reference(row: { id: number; objectKey: string; name: string; contentType: string; size: number; mediaAssetId?: number | null }) {
    return {
      id: row.id,
      url: await this.storage.signedReadUrl(row.objectKey),
      name: row.name,
      contentType: row.contentType,
      size: row.size,
      ...(row.mediaAssetId && row.mediaAssetId > 0 ? { mediaAssetId: row.mediaAssetId } : {}),
    };
  }
}
