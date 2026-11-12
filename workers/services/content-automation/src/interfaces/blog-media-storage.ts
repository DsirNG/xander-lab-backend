export interface StoredBlogMedia {
  url: string;
  storedName: string;
}

export interface BlogMediaStorage {
  signedUrl?(objectKey: string): Promise<string>;
  upload(objectKey: string, bytes: Uint8Array, contentType: string): Promise<StoredBlogMedia>;
  read(objectKey: string): Promise<{ body: ReadableStream<Uint8Array> } | null>;
  delete(objectKey: string): Promise<void>;
}
