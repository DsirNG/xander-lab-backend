import type { D1Binding } from "@dinqor/worker-runtime";
import type { PlatformFileAsset, PlatformFileRepository } from "../interfaces/platform-file-repository.js";

/** Keeps Platform-owned file metadata in its D1 database. */
export class D1PlatformFileMapper implements PlatformFileRepository {
  constructor(private readonly db: D1Binding) {}

  async create(asset: Omit<PlatformFileAsset, "createdAt">): Promise<void> {
    await this.db.prepare(`
      INSERT INTO platform_file_asset (
        stored_name, user_id, original_name, object_key, byte_size,
        content_type, extension, content_sha256
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      asset.storedName,
      asset.userId,
      asset.originalName,
      asset.objectKey,
      asset.byteSize,
      asset.contentType,
      asset.extension,
      asset.contentSha256,
    ).run();
  }

  find(storedName: string): Promise<PlatformFileAsset | null> {
    return this.db.prepare(`
      SELECT stored_name AS storedName, user_id AS userId, original_name AS originalName,
             object_key AS objectKey, byte_size AS byteSize, content_type AS contentType,
             extension, content_sha256 AS contentSha256, created_at AS createdAt
      FROM platform_file_asset WHERE stored_name = ?
    `).bind(storedName).first<PlatformFileAsset>();
  }

  async delete(storedName: string): Promise<void> {
    await this.db.prepare("DELETE FROM platform_file_asset WHERE stored_name = ?")
      .bind(storedName).run();
  }
}
