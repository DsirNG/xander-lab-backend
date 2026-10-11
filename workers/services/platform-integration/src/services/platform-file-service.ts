import type { PlatformFileAsset, PlatformFileRepository } from "../interfaces/platform-file-repository.js";
import {
  PlatformFileStorageError,
  type PlatformFileObject,
  type PlatformFileStorage,
} from "../interfaces/platform-file-storage.js";

const MAX_FILE_BYTES = 50 * 1024 * 1024;
const MAX_BATCH_FILES = 20;
const ALLOWED_CONTENT_TYPES = new Set([
  "image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf",
  "text/plain", "text/csv", "application/vnd.ms-excel",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/zip", "application/x-zip-compressed",
]);

export class PlatformFileError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "PlatformFileError";
  }
}

export interface UploadResponse {
  url: string;
  originalName: string;
  storedName: string;
  size: number;
  contentType: string;
  extension: string;
}

export interface PlatformFileDownload {
  asset: PlatformFileAsset;
  object: PlatformFileObject;
}

export interface PlatformFileServiceConfig {
  accessUrl?: string;
}

async function sha256Hex(value: Uint8Array): Promise<string> {
  const copy = new Uint8Array(value.byteLength);
  copy.set(value);
  const digest = await crypto.subtle.digest("SHA-256", copy.buffer);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function safeOriginalName(value: string): string {
  const filename = value.split(/[\\/]/).pop() ?? "file";
  const safe = filename.replace(/[\u0000-\u001f\u007f]/g, "_").trim();
  return safe.slice(0, 255) || "file";
}

function extensionOf(filename: string): string {
  const dot = filename.lastIndexOf(".");
  if (dot < 0 || dot === filename.length - 1) return "";
  const extension = filename.slice(dot + 1).toLowerCase();
  return /^[a-z0-9]{1,16}$/.test(extension) ? extension : "";
}

function objectPrefix(category: "upload" | "avatar" | "photo" | "video"): string {
  if (category === "upload") return "uploads";
  return category === "avatar" ? "avatars" : category === "video" ? "videos" : "photos";
}

function assertUserId(userId: number): void {
  if (!Number.isSafeInteger(userId) || userId < 1) throw new PlatformFileError(401, "未登录或登录已过期");
}

function logUploadFailure(phase: "oss_put" | "d1_insert", error: unknown): void {
  const details = error instanceof PlatformFileStorageError
    ? {
      phase,
      operation: error.operation,
      ...(error.status ? { status: error.status } : {}),
      ...(error.code ? { code: error.code } : {}),
      ...(error.requestId ? { requestId: error.requestId } : {}),
    }
    : { phase, errorName: error instanceof Error ? error.name : "UnknownError" };
  console.error("Platform file upload failed", details);
}

/** Owns file validation, OSS/D1 coordination, downloads, and legacy export content. */
export class PlatformFileService {
  constructor(
    private readonly repository: PlatformFileRepository,
    private readonly storage: PlatformFileStorage,
    private readonly config: PlatformFileServiceConfig = {},
  ) {}

  async upload(userId: number, file: File, category: "upload" | "avatar" | "photo" | "video"):
    Promise<UploadResponse> {
    assertUserId(userId);
    if (!(file instanceof File) || file.size === 0) throw new PlatformFileError(400, "文件不能为空");
    if (file.size > MAX_FILE_BYTES) throw new PlatformFileError(400, "文件大小超过限制（最大 50MB）");
    const contentType = file.type.trim().toLowerCase();
    if (!ALLOWED_CONTENT_TYPES.has(contentType)) {
      throw new PlatformFileError(400, `不支持的文件类型：${contentType || "unknown"}`);
    }

    const originalName = safeOriginalName(file.name);
    const extension = extensionOf(originalName);
    const storedName = `${crypto.randomUUID()}${extension ? `.${extension}` : ""}`;
    const objectKey = `${objectPrefix(category)}/${storedName}`;
    const bytes = new Uint8Array(await file.arrayBuffer());
    if (bytes.byteLength !== file.size || bytes.byteLength > MAX_FILE_BYTES) {
      throw new PlatformFileError(400, "文件大小超过限制（最大 50MB）");
    }
    const asset = {
      storedName,
      userId,
      originalName,
      objectKey,
      byteSize: bytes.byteLength,
      contentType,
      extension,
      contentSha256: await sha256Hex(bytes),
    };
    try {
      await this.storage.put(objectKey, bytes, contentType);
    } catch (error) {
      logUploadFailure("oss_put", error);
      try {
        await this.storage.delete(objectKey);
      } catch {
        console.error("Platform file upload cleanup failed", { phase: "oss_put" });
      }
      throw new PlatformFileError(503, "文件上传失败，请稍后重试");
    }
    try {
      await this.repository.create(asset);
    } catch (error) {
      logUploadFailure("d1_insert", error);
      try {
        await this.storage.delete(objectKey);
      } catch {
        console.error("Platform file upload cleanup failed", { phase: "d1_insert" });
      }
      throw new PlatformFileError(503, "文件上传失败，请稍后重试");
    }

    const accessUrl = (this.config.accessUrl ?? "/api/download").replace(/\/+$/, "");
    return {
      url: `${accessUrl}/${encodeURIComponent(storedName)}`,
      originalName,
      storedName,
      size: bytes.byteLength,
      contentType,
      extension,
    };
  }

  async uploadBatch(userId: number, files: File[]): Promise<UploadResponse[]> {
    if (files.length === 0 || files.length > MAX_BATCH_FILES) {
      throw new PlatformFileError(400, "批量上传文件数量无效");
    }
    if (files.reduce((total, file) => total + file.size, 0) > MAX_FILE_BYTES) {
      throw new PlatformFileError(413, "批量上传总大小超过 50MB");
    }
    const uploaded: UploadResponse[] = [];
    try {
      for (const file of files) uploaded.push(await this.upload(userId, file, "upload"));
      return uploaded;
    } catch (error) {
      for (const item of uploaded) {
        try {
          const asset = await this.repository.find(item.storedName);
          if (asset) await this.storage.delete(asset.objectKey);
          await this.repository.delete(item.storedName);
        } catch {
          // Keep the original error; storage lifecycle rules clean up an orphan.
        }
      }
      throw error;
    }
  }

  async download(storedName: string): Promise<PlatformFileDownload | null> {
    if (!/^[0-9a-f-]{36}(?:\.[a-z0-9]{1,16})?$/.test(storedName)) {
      throw new PlatformFileError(400, "非法的文件路径");
    }
    const asset = await this.repository.find(storedName);
    if (!asset) return null;
    const object = await this.storage.read(asset.objectKey, asset.byteSize);
    return object ? { asset, object } : null;
  }

  async downloadPublicImage(storedName: string): Promise<PlatformFileDownload | null> {
    if (!/^[0-9a-f-]{36}(?:\.[a-z0-9]{1,16})?$/.test(storedName)) return null;
    const asset = await this.repository.find(storedName);
    if (!asset || !asset.contentType.toLowerCase().startsWith("image/")) return null;
    const object = await this.storage.read(asset.objectKey, asset.byteSize);
    return object ? { asset, object } : null;
  }

  export(type: string, excel: boolean): { filename: string; contentType: string; body: Uint8Array } {
    if (!/^[a-zA-Z0-9_-]{1,40}$/.test(type)) throw new PlatformFileError(400, "导出类型不正确");
    let csv: string;
    switch (type) {
      case "blog":
        csv = "ID,标题,分类,作者,发布日期,浏览量\n"
          + "1,Spring Boot 入门指南,后端开发,DinQorAI,2024-01-15,1280\n"
          + "2,React 18 新特性详解,前端开发,DinQorAI,2024-02-20,980\n"
          + "3,MySQL 性能优化实践,数据库,DinQorAI,2024-03-10,756\n";
        break;
      case "user":
        csv = "ID,用户名,昵称,角色,注册时间\n"
          + "1,admin,管理员,ADMIN,2024-01-01\n"
          + "2,dinqorai,DinQorAI,USER,2024-01-02\n"
          + "3,demo,演示用户,GUEST,2024-01-03\n";
        break;
      default:
        csv = `导出类型,导出时间\n${type},${new Date().toISOString()}\n`;
    }
    return {
      filename: `${type}_export.${excel ? "xlsx" : "csv"}`,
      contentType: excel
        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        : "text/csv;charset=UTF-8",
      body: new TextEncoder().encode(csv),
    };
  }
}
