export interface JuejinTagCandidate {
  id: string;
  name: string;
}

export interface JuejinDraftInput {
  title: string;
  briefContent: string;
  markdownContent: string;
  categoryId: string;
  tagIds: string[];
  coverImage: string;
}

export interface JuejinPublicationPort {
  searchTags(keyword: string, limit: number): Promise<JuejinTagCandidate[]>;
  createDraft(credential: Record<string, string>, input: JuejinDraftInput): Promise<string>;
  publishDraft(credential: Record<string, string>, draftId: string): Promise<string>;
}
