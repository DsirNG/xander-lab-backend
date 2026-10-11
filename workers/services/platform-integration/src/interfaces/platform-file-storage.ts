export interface PlatformFileObject {
  body: ReadableStream<Uint8Array> | null;
  contentType: string | null;
}

export interface PlatformFileStorage {
  put(objectKey: string, body: Uint8Array, contentType: string): Promise<void>;
  read(objectKey: string, maxBytes: number): Promise<PlatformFileObject | null>;
  delete(objectKey: string): Promise<void>;
}

export class PlatformFileStorageError extends Error {
  constructor(
    readonly operation: "put" | "read" | "delete",
    readonly status?: number,
    readonly code?: string,
    readonly requestId?: string,
  ) {
    super(`OSS ${operation} failed${status ? ` with HTTP ${status}` : ""}${code ? ` (${code})` : ""}`);
    this.name = "PlatformFileStorageError";
  }
}
