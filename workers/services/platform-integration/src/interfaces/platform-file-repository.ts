export interface PlatformFileAsset {
  storedName: string;
  userId: number;
  originalName: string;
  objectKey: string;
  byteSize: number;
  contentType: string;
  extension: string;
  contentSha256: string;
  createdAt: string;
}

/** D1 metadata port for OSS-backed Platform Integration files. */
export interface PlatformFileRepository {
  create(asset: Omit<PlatformFileAsset, "createdAt">): Promise<void>;
  find(storedName: string): Promise<PlatformFileAsset | null>;
  delete(storedName: string): Promise<void>;
}
