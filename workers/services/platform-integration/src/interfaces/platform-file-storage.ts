export interface PlatformFileObject {
  body: ReadableStream<Uint8Array> | null;
  contentType: string | null;
}

export interface PlatformFileStorage {
  put(objectKey: string, body: Uint8Array, contentType: string): Promise<void>;
  read(objectKey: string, maxBytes: number): Promise<PlatformFileObject | null>;
  delete(objectKey: string): Promise<void>;
}
