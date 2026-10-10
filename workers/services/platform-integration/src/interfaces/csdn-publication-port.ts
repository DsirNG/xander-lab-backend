export interface CsdnArticleInput {
  userId: number;
  postId: number;
  title: string;
  summary: string;
  content: string;
  category: string | null;
  coverImage: string | null;
  tags: string[];
}

export interface CsdnPublicationResult {
  externalId: string;
  url: string;
}

export interface CsdnPublicationPort {
  publish(credential: Record<string, string>, input: CsdnArticleInput): Promise<CsdnPublicationResult>;
}

export class CsdnProviderError extends Error {
  constructor(readonly sessionExpired: boolean, message = "CSDN 发布服务暂不可用") {
    super(message);
    this.name = "CsdnProviderError";
  }
}
