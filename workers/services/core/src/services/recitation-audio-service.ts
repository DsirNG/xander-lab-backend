import type { RecitationAudioStorage, RecitationUploadResult } from "../interfaces/recitation-audio.js";
import type { RecitationAttemptRow, RecitationRepository } from "../interfaces/recitation-repository.js";
import { RecitationError } from "./recitation-service.js";

const AUDIO_EXTENSIONS = new Set(["mp3", "aac", "wav", "m4a", "ogg", "opus", "amr"]);

export class RecitationAudioService {
  constructor(
    private readonly repository: RecitationRepository,
    private readonly storage: RecitationAudioStorage,
    private readonly enabled: boolean,
    private readonly maxAudioBytes = 20 * 1024 * 1024,
  ) {}

  async createAttempt(
    userId: number,
    materialId: number,
    file: { name: string; type: string; size: number; bytes: Uint8Array },
    clientRequestId: string,
  ): Promise<RecitationUploadResult> {
    if (!this.enabled) throw new RecitationError(503, "背诵音转文功能尚未启用");
    if (!Number.isSafeInteger(userId) || userId <= 0) throw new RecitationError(401, "未登录或登录已过期");
    const material = await this.repository.findMaterial(userId, materialId);
    if (!material) throw new RecitationError(404, "背诵材料不存在");
    const fileInfo = this.validateFile(file);
    const requestId = clientRequestId.trim();
    if (!requestId) throw new RecitationError(400, "录音请求标识不能为空");
    if (requestId.length > 64) throw new RecitationError(400, "录音请求标识不能超过64个字符");

    const existing = await this.repository.findAttemptByRequestId(userId, requestId);
    if (existing) return this.sameMaterial(existing, materialId);
    let attempt;
    try {
      attempt = await this.repository.createAttempt(userId, materialId, requestId);
    } catch {
      const raced = await this.repository.findAttemptByRequestId(userId, requestId);
      if (raced) return this.sameMaterial(raced, materialId);
      throw new RecitationError(503, "无法创建背诵任务");
    }
    if (!attempt) throw new RecitationError(503, "无法创建背诵任务");

    const objectKey = `${userId}/${crypto.randomUUID()}.${fileInfo.extension}`;
    let objectUploaded = false;
    try {
      await this.storage.write(objectKey, file.bytes, fileInfo.contentType);
      objectUploaded = true;
      if (!await this.repository.setAttemptAudio(userId, attempt.id, objectKey)) {
        throw new Error("Recitation attempt upload state was replaced");
      }
      const ready = await this.repository.findAttempt(userId, attempt.id);
      if (!ready) throw new Error("Recitation attempt disappeared after upload");
      return { attempt: ready, replayed: false };
    } catch {
      await this.repository.failAttempt(userId, attempt.id, "录音上传失败，请稍后重试");
      if (objectUploaded) {
        try { await this.storage.delete(objectKey); } catch { /* OSS lifecycle rules clean up orphaned audio. */ }
      }
      throw new RecitationError(503, "录音上传失败，请稍后重试");
    }
  }

  private sameMaterial(attempt: RecitationAttemptRow, materialId: number): RecitationUploadResult {
    if (attempt.materialId !== materialId) throw new RecitationError(409, "录音请求标识已用于另一条知识");
    return { attempt, replayed: true };
  }

  private validateFile(file: { name: string; type: string; size: number; bytes: Uint8Array }) {
    if (!file || file.size <= 0 || file.bytes.byteLength !== file.size) throw new RecitationError(400, "录音文件不能为空");
    if (file.size > this.maxAudioBytes) {
      const megabytes = Math.floor(this.maxAudioBytes / 1024 / 1024);
      throw new RecitationError(400, `录音文件不能超过 ${megabytes}MB`);
    }
    const name = file.name.trim();
    const extension = name.includes(".") ? name.slice(name.lastIndexOf(".") + 1).toLowerCase() : "";
    const contentType = file.type.trim().slice(0, 120) || "application/octet-stream";
    if (!AUDIO_EXTENSIONS.has(extension) && !contentType.toLowerCase().startsWith("audio/")) {
      throw new RecitationError(400, "仅支持 mp3、aac、wav、m4a、ogg、opus 或 amr 录音");
    }
    return { extension: AUDIO_EXTENSIONS.has(extension) ? extension : "audio", contentType };
  }
}
