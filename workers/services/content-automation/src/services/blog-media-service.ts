import type { BlogMediaAsset, BlogMediaRepository } from "../interfaces/blog-media-repository.js";
import type { BlogMediaStorage } from "../interfaces/blog-media-storage.js";

export class BlogMediaInputError extends Error {
  constructor(message: string, readonly status = 400) { super(message); }
}

const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
const SUPPORTED_UPLOAD_TYPES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);
const PUBLIC_IMAGE_OBJECT_KEY = /^[1-9]\d{0,17}\/\d{4}\/(?:0[1-9]|1[0-2])\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:jpg|png|webp|gif)$/;

export function isPublicBlogImageObjectKey(value: string): boolean {
  return PUBLIC_IMAGE_OBJECT_KEY.test(value);
}

function dimension(width: number, height: number): { width: number; height: number } | null {
  return Number.isSafeInteger(width) && width > 0 && Number.isSafeInteger(height) && height > 0
    ? { width, height } : null;
}

function imageDimensions(bytes: Uint8Array, contentType: string): { width: number; height: number } | null {
  if (contentType === "image/png" && bytes.length >= 24
    && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    return dimension(view.getUint32(16), view.getUint32(20));
  }
  if (contentType === "image/gif" && bytes.length >= 10
    && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) {
    return dimension(bytes[6] | (bytes[7] << 8), bytes[8] | (bytes[9] << 8));
  }
  if (contentType === "image/webp" && bytes.length >= 30
    && String.fromCharCode(...bytes.subarray(0, 4)) === "RIFF"
    && String.fromCharCode(...bytes.subarray(8, 12)) === "WEBP") {
    const chunk = String.fromCharCode(...bytes.subarray(12, 16));
    if (chunk === "VP8X") {
      const width = 1 + bytes[24] + (bytes[25] << 8) + (bytes[26] << 16);
      const height = 1 + bytes[27] + (bytes[28] << 8) + (bytes[29] << 16);
      return dimension(width, height);
    }
    if (chunk === "VP8L" && bytes.length >= 25 && bytes[20] === 0x2f) {
      const width = 1 + bytes[21] + ((bytes[22] & 0x3f) << 8);
      const height = 1 + ((bytes[22] >> 6) & 0x03) + (bytes[23] << 2) + ((bytes[24] & 0x0f) << 10);
      return dimension(width, height);
    }
    if (chunk === "VP8 " && bytes.length >= 30 && bytes[23] === 0x9d && bytes[24] === 0x01 && bytes[25] === 0x2a) {
      const width = (bytes[26] | (bytes[27] << 8)) & 0x3fff;
      const height = (bytes[28] | (bytes[29] << 8)) & 0x3fff;
      return dimension(width, height);
    }
    return null;
  }
  if (contentType === "image/jpeg" && bytes.length >= 4 && bytes[0] === 0xff && bytes[1] === 0xd8) {
    const startOfFrame = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);
    let offset = 2;
    while (offset + 4 <= bytes.length) {
      if (bytes[offset] !== 0xff) return null;
      while (bytes[offset] === 0xff) offset++;
      const marker = bytes[offset++];
      if (marker === 0xd9 || marker === 0xda) return null;
      if (marker === 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
      const length = (bytes[offset] << 8) | bytes[offset + 1];
      if (length < 2 || offset + length > bytes.length) return null;
      if (startOfFrame.has(marker) && length >= 7) {
        const height = (bytes[offset + 3] << 8) | bytes[offset + 4];
        const width = (bytes[offset + 5] << 8) | bytes[offset + 6];
        return dimension(width, height);
      }
      offset += length;
    }
  }
  return null;
}

function hasExpectedSignature(bytes: Uint8Array, contentType: string): boolean {
  if (contentType === "image/png") return bytes.length >= 8
    && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((byte, i) => bytes[i] === byte);
  if (contentType === "image/jpeg") return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (contentType === "image/gif") return bytes.length >= 6
    && (String.fromCharCode(...bytes.subarray(0, 6)) === "GIF87a" || String.fromCharCode(...bytes.subarray(0, 6)) === "GIF89a");
  if (contentType === "image/webp") return bytes.length >= 12
    && String.fromCharCode(...bytes.subarray(0, 4)) === "RIFF"
    && String.fromCharCode(...bytes.subarray(8, 12)) === "WEBP";
  return false;
}

function normalizedFileName(value: string | null | undefined, fallback: string): string {
  const lastPart = (value ?? "").replace(/[\u0000-\u001f\u007f]/g, "").split(/[\\/]/).at(-1)?.trim() ?? "";
  return (lastPart || fallback).slice(0, 255);
}

function safeUserId(userId: number): void {
  if (!Number.isSafeInteger(userId) || userId < 1) throw new BlogMediaInputError("用户 ID 无效", 401);
}

function agentImage(asset: BlogMediaAsset) {
  let title: string | null = null;
  let prompt: string | null = null;
  let model: string | null = null;
  let size: string | null = null;
  if (asset.generationMeta) {
    try {
      const value: unknown = JSON.parse(asset.generationMeta);
      if (value && typeof value === "object" && !Array.isArray(value)) {
        const data = value as Record<string, unknown>;
        title = typeof data.title === "string" ? data.title : null;
        prompt = typeof data.prompt === "string" ? data.prompt : null;
        model = typeof data.model === "string" ? data.model : null;
        size = typeof data.size === "string" ? data.size : null;
      }
    } catch { /* malformed legacy metadata must not hide the image */ }
  }
  return {
    id: asset.id, url: asset.url, storedName: asset.storedName, title: title?.trim() || asset.originalName,
    prompt, model, size, width: asset.width, height: asset.height, createdAt: asset.createdAt,
  };
}

export class BlogMediaService {
  constructor(private readonly repository: BlogMediaRepository, private readonly storage: BlogMediaStorage) {}

  images(userId: number, scope: string, keyword: string | null) {
    safeUserId(userId);
    const trimmed = keyword?.trim().slice(0, 255) || null;
    return this.repository.listImages(userId, scope === "gif" ? "image/gif" : null, trimmed,
      scope === "recent" ? 24 : 60);
  }

  async agentImages(userId: number, page: number, size: number) {
    safeUserId(userId);
    const current = Number.isSafeInteger(page) ? Math.max(page, 1) : 1;
    const safeSize = Number.isSafeInteger(size) ? Math.min(Math.max(size, 1), 60) : 24;
    const [total, records] = await Promise.all([
      this.repository.countAgentImages(userId),
      this.repository.listAgentImages(userId, safeSize, (current - 1) * safeSize),
    ]);
    const pages = total === 0 ? 0 : Math.ceil(total / safeSize);
    return { records: records.map(agentImage), total, current, pages, size: safeSize, hasMore: current * safeSize < total };
  }

  findOwned(userId: number, id: number) {
    safeUserId(userId);
    return this.repository.findOwned(userId, id);
  }

  async deleteOwned(userId: number, id: number): Promise<boolean> {
    safeUserId(userId);
    const asset = await this.repository.findOwned(userId, id);
    if (!asset) return false;
    if (asset.storedName) await this.storage.delete(asset.storedName);
    return this.repository.deleteOwned(userId, id);
  }

  async publicImage(objectKey: string) {
    if (!isPublicBlogImageObjectKey(objectKey)) return null;
    const asset = await this.repository.findPublicByStoredName(objectKey);
    if (!asset || !SUPPORTED_UPLOAD_TYPES.has(asset.contentType)
      || !Number.isSafeInteger(asset.size) || asset.size < 1 || asset.size > MAX_IMAGE_BYTES) return null;
    const stored = await this.storage.read(objectKey);
    if (!stored) return null;
    return { body: stored.body, contentType: asset.contentType, size: asset.size };
  }

  listByTask(userId: number, taskId: number) {
    safeUserId(userId);
    return this.repository.listByTask(userId, taskId);
  }

  async findReusableGenerated(userId: number, title: string) {
    safeUserId(userId);
    const target = title.trim();
    if (!target) return null;
    for (const asset of await this.repository.listRecentGenerated(userId, 100)) {
      try {
        const metadata: unknown = asset.generationMeta ? JSON.parse(asset.generationMeta) : null;
        if (metadata && typeof metadata === "object" && !Array.isArray(metadata)
          && (metadata as Record<string, unknown>).title === target) return asset;
      } catch { /* ignore malformed legacy metadata */ }
    }
    return null;
  }

  recordStored(userId: number, stored: {
    url: string; originalName: string; storedName?: string | null; size: number; contentType: string;
  }) {
    safeUserId(userId);
    if (!stored || typeof stored !== "object" || typeof stored.url !== "string"
      || typeof stored.originalName !== "string" || typeof stored.contentType !== "string"
      || !Number.isSafeInteger(stored.size) || stored.size < 1) {
      throw new BlogMediaInputError("媒体信息无效");
    }
    let url: URL;
    try { url = new URL(stored.url); } catch { throw new BlogMediaInputError("媒体 URL 无效"); }
    if (url.protocol !== "https:" || url.username || url.password || !stored.contentType.startsWith("image/")) {
      throw new BlogMediaInputError("仅接受 HTTPS 图片素材");
    }
    return this.repository.insert({
      userId, url: url.toString(), originalName: normalizedFileName(stored.originalName, "image"),
      storedName: typeof stored.storedName === "string" ? stored.storedName.slice(0, 255) : null,
      size: stored.size, contentType: stored.contentType.slice(0, 100), sourceType: "user_upload",
    });
  }

  async uploadImage(input: {
    userId: number;
    bytes: Uint8Array;
    originalName?: string | null;
    contentType: string;
    sourceType?: "user_upload" | "agent_generated";
    agentTaskId?: number | null;
    generationMeta?: string | null;
  }): Promise<BlogMediaAsset> {
    safeUserId(input.userId);
    if (!(input.bytes instanceof Uint8Array) || input.bytes.byteLength === 0) {
      throw new BlogMediaInputError("图片内容不能为空");
    }
    if (input.bytes.byteLength > MAX_IMAGE_BYTES) throw new BlogMediaInputError("图片大小不能超过 20MB", 413);
    const contentType = input.contentType.trim().toLowerCase();
    if (!SUPPORTED_UPLOAD_TYPES.has(contentType) || !hasExpectedSignature(input.bytes, contentType)) {
      throw new BlogMediaInputError("仅支持有效的 PNG、JPEG、WebP 或 GIF 图片");
    }
    if (input.sourceType === "agent_generated" && input.agentTaskId !== null && input.agentTaskId !== undefined
      && (!Number.isSafeInteger(input.agentTaskId) || input.agentTaskId < 1)) {
      throw new BlogMediaInputError("Agent 任务 ID 无效");
    }
    const generationMeta = input.generationMeta?.trim() || null;
    if (generationMeta && generationMeta.length > 20_000) throw new BlogMediaInputError("生成元数据过长");
    if (generationMeta) {
      try { JSON.parse(generationMeta); } catch { throw new BlogMediaInputError("生成元数据格式无效"); }
    }
    const suffix = contentType === "image/jpeg" ? "jpg" : contentType.slice("image/".length);
    const now = new Date();
    const objectKey = `${input.userId}/${now.getUTCFullYear()}/${String(now.getUTCMonth() + 1).padStart(2, "0")}/${crypto.randomUUID()}.${suffix}`;
    const stored = await this.storage.upload(objectKey, input.bytes, contentType);
    try {
      const dimensions = imageDimensions(input.bytes, contentType);
      return await this.repository.insert({
        userId: input.userId,
        url: stored.url,
        originalName: normalizedFileName(input.originalName, `image.${suffix}`),
        storedName: stored.storedName,
        size: input.bytes.byteLength,
        contentType,
        width: dimensions?.width ?? null,
        height: dimensions?.height ?? null,
        sourceType: input.sourceType ?? "user_upload",
        agentTaskId: input.agentTaskId ?? null,
        generationMeta: input.sourceType === "agent_generated" ? generationMeta : null,
      });
    } catch (error) {
      await this.storage.delete(objectKey).catch(() => undefined);
      throw error;
    }
  }
}
