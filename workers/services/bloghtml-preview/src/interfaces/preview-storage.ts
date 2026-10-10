export interface PreviewStorage {
  put(objectKey: string, code: string): Promise<void>;
  read(objectKey: string, maxBytes: number): Promise<string | null>;
  delete(objectKey: string): Promise<void>;
}
