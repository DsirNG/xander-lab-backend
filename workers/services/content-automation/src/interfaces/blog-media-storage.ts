export interface StoredBlogMedia {
  url: string;
  storedName: string;
}

export interface BlogMediaStorage {
  upload(objectKey: string, bytes: Uint8Array, contentType: string): Promise<StoredBlogMedia>;
  delete(objectKey: string): Promise<void>;
}
