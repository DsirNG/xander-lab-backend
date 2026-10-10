import { BlogPostCreationService } from "./blog-post-creation-service.js";
import { BlogManagementService } from "./blog-management-service.js";

/** MCP operations over Content Automation's owner-scoped article use cases. */
export class BlogMcpService {
  constructor(
    private readonly creation: BlogPostCreationService,
    private readonly management: BlogManagementService,
  ) {}

  async list(userId: number, page: number, size: number, search: string) {
    this.validateOwner(userId);
    const result = await this.management.listMine(userId, null, search, page, size);
    return {
      ...result,
      records: result.records.map(({ content: _content, ...metadata }) => metadata),
    };
  }

  get(userId: number, postId: number) {
    this.validateOwner(userId);
    return this.management.findMine(userId, postId);
  }

  create(userId: number, input: {
    idempotencyKey: string; title: string; content: string; summary?: string | null;
    categoryId?: string | null; tags?: string[] | null; publish: boolean;
  }) {
    this.validateOwner(userId);
    return this.creation.create({
      userId, idempotencyKey: input.idempotencyKey, title: input.title, content: input.content,
      summary: input.summary, categoryId: input.categoryId?.trim() || "backend", tags: input.tags,
      publish: input.publish,
    });
  }

  update(userId: number, postId: number, input: Record<string, unknown>) {
    this.validateOwner(userId);
    return this.management.update(userId, postId, input);
  }

  async delete(userId: number, postId: number): Promise<void> {
    this.validateOwner(userId);
    await this.management.moveToTrash(userId, postId);
  }

  private validateOwner(userId: number): void {
    if (!Number.isSafeInteger(userId) || userId <= 0) throw new Error("Invalid MCP article owner");
  }
}
