import type { PublishingPlatform } from "../interfaces/platform-credential-repository.js";
import type { PlatformMcpBillingPort, PlatformMcpBlogPort, PlatformMcpPublicationPort } from "../interfaces/platform-mcp-ports.js";
import { PlatformMcpPortError } from "../interfaces/platform-mcp-ports.js";
import type { PlatformAuthorizationService } from "./platform-authorization-service.js";
import type { PlatformCredentialService } from "./platform-credential-service.js";

export interface McpPrincipal {
  userId: number;
  clientId: string;
  scopes: string[];
}

export interface McpToolDefinition {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export class McpToolError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "McpToolError";
  }
}

export class PlatformMcpService {
  constructor(
    private readonly blog: PlatformMcpBlogPort,
    private readonly publication: PlatformMcpPublicationPort,
    private readonly billing: PlatformMcpBillingPort,
    private readonly authorization: PlatformAuthorizationService,
    private readonly credentials: PlatformCredentialService,
    private readonly enabled: Record<"blog" | "csdn" | "dual" | "juejin", boolean>,
  ) {}

  isEnabled(path: string): boolean {
    if (path === "/mcp" || path === "/api/mcp") return this.enabled.blog;
    if (path === "/api/mcp/csdn") return this.enabled.csdn;
    if (path === "/api/mcp/dual") return this.enabled.dual;
    if (path === "/api/mcp/juejin") return this.enabled.juejin;
    return false;
  }

  tools(path: string): McpToolDefinition[] {
    const blogRead: McpToolDefinition[] = [
      { name: "list_posts", description: "Lists the authorized user's blog posts for topic deduplication and context selection.", inputSchema: schema({
        page: { type: "integer", minimum: 1 }, size: { type: "integer", minimum: 1, maximum: 10 }, search: { type: "string" },
      }) },
      { name: "get_post", description: "Reads one of the authorized user's blog posts by id.", inputSchema: schema({ postId: { type: "integer" } }, ["postId"]) },
    ];
    const blogWrite: McpToolDefinition[] = [
      { name: "create_draft", description: "Creates an unpublished blog draft.", inputSchema: postSchema() },
      { name: "publish_post", description: "Publishes a blog post.", inputSchema: postSchema() },
      { name: "update_post", description: "Updates supplied fields of an existing blog post. Providing tags replaces all tags.", inputSchema: schema({
        postId: { type: "integer" }, title: { type: "string" }, titleBase64: { type: "string" },
        summary: { type: "string" }, summaryBase64: { type: "string" }, content: { type: "string" },
        contentBase64: { type: "string" }, categoryId: { type: "string" },
        tags: { type: "array", items: { type: "string" } },
      }, ["postId"]) },
      { name: "delete_post", description: "Moves one blog post to the recycle bin. Requires confirm=true.", inputSchema: schema({
        postId: { type: "integer" }, confirm: { type: "boolean" },
      }, ["postId", "confirm"]) },
    ];
    if (path === "/mcp" || path === "/api/mcp") return [...blogRead, ...blogWrite];
    if (path === "/api/mcp/csdn") return [
      { name: "get_csdn_auth_status", description: "Returns whether the current user has authorized CSDN sync.", inputSchema: schema({}) },
      { name: "start_csdn_login", description: "Starts CSDN QR authorization and returns the QR image data URL.", inputSchema: schema({}) },
      { name: "sync_post_to_csdn", description: "Synchronizes one owned blog post to CSDN.", inputSchema: schema({ postId: { type: "integer" } }, ["postId"]) },
    ];
    if (path === "/api/mcp/juejin") return [
      { name: "get_juejin_auth_status", description: "Returns whether the current user has an active Juejin account binding.", inputSchema: schema({}) },
      { name: "publish_article_to_juejin", description: "Creates a local draft and publishes it to the bound Juejin account.", inputSchema: schema({
        title: { type: "string" }, content: { type: "string" }, contentBase64: { type: "string" },
        summary: { type: "string" }, juejinCategoryId: { type: "string" },
        juejinTagIds: { type: "array", items: { type: "string" } },
      }, ["title"]) },
      { name: "sync_post_to_juejin", description: "Synchronizes one owned local blog post to Juejin.", inputSchema: schema({
        postId: { type: "integer" }, juejinCategoryId: { type: "string" },
        juejinTagIds: { type: "array", items: { type: "string" } },
      }, ["postId"]) },
    ];
    if (path === "/api/mcp/dual") return [
      { name: "start_csdn_login", description: "Starts CSDN QR authorization for the bound DinQorAI user.", inputSchema: schema({}) },
      { name: "publish_to_both", description: "Publishes one article to the local blog and its bound CSDN account.", inputSchema: schema({
        title: { type: "string" }, content: { type: "string" }, contentBase64: { type: "string" },
        summary: { type: "string" }, categoryId: { type: "string" }, tags: { type: "array", items: { type: "string" } },
      }, ["title"]) },
    ];
    return [];
  }

  async call(path: string, principal: McpPrincipal, callId: string | number, name: string, args: Record<string, unknown>) {
    if (!this.isEnabled(path)) throw new McpToolError("MCP_DISABLED", "This MCP server is disabled");
    switch (path) {
      case "/mcp":
      case "/api/mcp":
        return this.callBlog(principal, callId, name, args);
      case "/api/mcp/csdn":
        return this.callCsdn(principal, callId, name, args);
      case "/api/mcp/juejin":
        return this.callJuejin(principal, callId, name, args);
      case "/api/mcp/dual":
        return this.callDual(principal, callId, name, args);
      default:
        throw new McpToolError("UNKNOWN_SERVER", "Unknown MCP server");
    }
  }

  private async callBlog(principal: McpPrincipal, callId: string | number, name: string, args: Record<string, unknown>) {
    if (name === "list_posts") {
      this.requireScope(principal, "blog:read");
      const posts = await this.blog.list(principal.userId, integerArg(args.page, 1), integerArg(args.size, 10, 10), stringArg(args.search, "", 200));
      return result({ ok: true, posts });
    }
    if (name === "get_post") {
      this.requireScope(principal, "blog:read");
      return result({ ok: true, post: await this.blog.get(principal.userId, positiveIntegerArg(args.postId)) });
    }
    if (name === "create_draft" || name === "publish_post") {
      this.requireScope(principal, "blog:write");
      const content = readContent(args);
      const post = await this.blog.create({
        userId: principal.userId, idempotencyKey: await this.idempotencyKey(principal, callId),
        title: stringArg(args.title, "", 300, true), content,
        summary: optionalString(args.summary, 2_000) ?? null,
        categoryId: stringArg(args.categoryId, "backend", 200), tags: stringArray(args.tags, 8, 100) ?? null,
        publish: name === "publish_post",
      });
      return result({ ok: true, postId: objectId(post), published: name === "publish_post" });
    }
    if (name === "update_post") {
      this.requireScope(principal, "blog:write");
      const input: Record<string, unknown> = {};
      const title = optionalString(args.title, 300) ?? decodeOptionalBase64(args.titleBase64, "titleBase64");
      const summary = optionalString(args.summary, 2_000) ?? decodeOptionalBase64(args.summaryBase64, "summaryBase64");
      if (title !== undefined && title !== null) input.title = title;
      if (summary !== undefined && summary !== null) input.summary = summary;
      if (args.content !== undefined || args.contentBase64 !== undefined) input.content = readContent(args);
      if (args.categoryId !== undefined && args.categoryId !== null) input.categoryId = stringArg(args.categoryId, "", 200, true);
      if (args.tags !== undefined && args.tags !== null) input.tags = stringArray(args.tags, 8, 100);
      const post = await this.blog.update(principal.userId, positiveIntegerArg(args.postId), input);
      return result({ ok: true, postId: objectId(post), updated: true });
    }
    if (name === "delete_post") {
      this.requireScope(principal, "blog:write");
      if (args.confirm !== true) return result({ ok: false, error: "CONFIRMATION_REQUIRED", message: "Set confirm=true to move this post to the recycle bin." }, true);
      const postId = positiveIntegerArg(args.postId);
      await this.blog.delete(principal.userId, postId);
      return result({ ok: true, postId, deleted: true });
    }
    throw new McpToolError("UNKNOWN_TOOL", `Unknown blog tool: ${name}`);
  }

  private async callCsdn(principal: McpPrincipal, callId: string | number, name: string, args: Record<string, unknown>) {
    if (name === "get_csdn_auth_status") {
      this.requireScope(principal, "csdn:status");
      const state = await this.authorization.status(principal.userId, "csdn");
      return result({ ok: true, authorized: state.status === "AUTHORIZED" });
    }
    if (name === "start_csdn_login") {
      this.requireScope(principal, "csdn:authorize");
      const state = await this.authorization.start(principal.userId, "csdn");
      return result({ ok: true, status: state.status, qrCodeDataUrl: state.qrCodeDataUrl });
    }
    if (name === "sync_post_to_csdn") {
      this.requireScope(principal, "csdn:publish");
      const postId = positiveIntegerArg(args.postId);
      const publication = await this.publication.sync({ userId: principal.userId, postId, platform: "csdn" });
      await this.bill(principal.userId, postId, "csdn-publish", "CSDN 发布成功");
      return result({ ok: true, platform: publication.platform, externalId: publication.externalId, url: publication.url });
    }
    throw new McpToolError("UNKNOWN_TOOL", `Unknown CSDN tool: ${name}`);
  }

  private async callJuejin(principal: McpPrincipal, callId: string | number, name: string, args: Record<string, unknown>) {
    if (name === "get_juejin_auth_status") {
      this.requireScope(principal, "juejin:status");
      const state = await this.credentials.status(principal.userId, "juejin");
      return result({ ok: true, status: state.status });
    }
    if (name === "sync_post_to_juejin") {
      this.requireScope(principal, "juejin:publish");
      const postId = positiveIntegerArg(args.postId);
      const publication = await this.publication.sync({
        userId: principal.userId, postId, platform: "juejin",
        categoryId: optionalString(args.juejinCategoryId, 200) ?? null, tagIds: stringArray(args.juejinTagIds, 50, 200) ?? null,
      });
      await this.bill(principal.userId, postId, "juejin-publish", "掘金发布成功");
      return result({ ok: true, externalId: publication.externalId, url: publication.url });
    }
    if (name === "publish_article_to_juejin") {
      this.requireScope(principal, "juejin:publish");
      this.requireScope(principal, "blog:write");
      const content = readContent(args);
      const draft = await this.blog.create({
        userId: principal.userId, idempotencyKey: await this.idempotencyKey(principal, callId),
        title: stringArg(args.title, "", 300, true), content, summary: optionalString(args.summary, 2_000) ?? null,
        categoryId: "backend", tags: [], publish: false,
      });
      const postId = objectId(draft);
      try {
        const publication = await this.publication.sync({
          userId: principal.userId, postId, platform: "juejin",
          categoryId: optionalString(args.juejinCategoryId, 200) ?? null, tagIds: stringArray(args.juejinTagIds, 50, 200) ?? null,
        });
        await this.bill(principal.userId, postId, "juejin-publish", "掘金发布成功");
        return result({ ok: true, externalId: publication.externalId, url: publication.url });
      } catch (error) {
        return result({ ok: false, localPostId: postId, error: safeMessage(error) }, true);
      }
    }
    throw new McpToolError("UNKNOWN_TOOL", `Unknown Juejin tool: ${name}`);
  }

  private async callDual(principal: McpPrincipal, callId: string | number, name: string, args: Record<string, unknown>) {
    if (name === "start_csdn_login") {
      this.requireScope(principal, "csdn:authorize");
      const state = await this.authorization.start(principal.userId, "csdn");
      return result({ ok: true, status: state.status, qrCodeDataUrl: state.qrCodeDataUrl });
    }
    if (name === "publish_to_both") {
      this.requireScope(principal, "publish:dual");
      this.requireScope(principal, "blog:write");
      this.requireScope(principal, "csdn:publish");
      const content = readContent(args);
      const post = await this.blog.create({
        userId: principal.userId, idempotencyKey: await this.idempotencyKey(principal, callId),
        title: stringArg(args.title, "", 300, true), content, summary: optionalString(args.summary, 2_000) ?? null,
        categoryId: stringArg(args.categoryId, "backend", 200), tags: stringArray(args.tags, 8, 100) ?? null, publish: true,
      });
      const localPostId = objectId(post);
      try {
        const publication = await this.publication.sync({ userId: principal.userId, postId: localPostId, platform: "csdn" });
        await this.bill(principal.userId, localPostId, "csdn-publish", "CSDN 发布成功");
        return result({ ok: true, localPostId, csdnPublished: true, csdnExternalId: publication.externalId, csdnUrl: publication.url, csdnError: null });
      } catch (error) {
        return result({ ok: false, localPostId, csdnPublished: false, csdnExternalId: null, csdnUrl: null, csdnError: safeMessage(error) });
      }
    }
    throw new McpToolError("UNKNOWN_TOOL", `Unknown dual-publish tool: ${name}`);
  }

  private async bill(userId: number, postId: number, bizType: "csdn-publish" | "juejin-publish", remark: string): Promise<void> {
    const points = await this.billing.flatPoints(bizType);
    if (!Number.isSafeInteger(points) || points < 0) throw new PlatformMcpPortError(502, "Billing price is invalid");
    await this.billing.charge({
      userId, bizType, bizKey: `${bizType.startsWith("csdn") ? "csdn" : "juejin"}:${postId}`,
      bizRef: `post:${postId}`, points, remark,
    });
  }

  private requireScope(principal: McpPrincipal, scope: string): void {
    if (!principal.scopes.includes(scope)) throw new McpToolError("INSUFFICIENT_SCOPE", `MCP scope is required: ${scope}`);
  }

  private async idempotencyKey(principal: McpPrincipal, callId: string | number): Promise<string> {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${principal.clientId}:${callId}`)));
    let binary = "";
    for (const byte of digest) binary += String.fromCharCode(byte);
    return `mcp:${btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "")}`;
  }
}

function schema(properties: Record<string, unknown>, required: string[] = []): Record<string, unknown> {
  return { type: "object", properties, ...(required.length ? { required } : {}) };
}
function postSchema(): Record<string, unknown> {
  return schema({ title: { type: "string" }, content: { type: "string" },
    contentBase64: { type: "string", description: "UTF-8 article content encoded as Base64. Use only when an upstream proxy blocks literal content." },
    summary: { type: "string" }, categoryId: { type: "string" }, tags: { type: "array", items: { type: "string" } }, }, ["title"]);
}
function result(data: Record<string, unknown>, isError = false) {
  return { structuredContent: data, content: [{ type: "text" as const, text: JSON.stringify(data) }], ...(isError ? { isError: true } : {}) };
}
function positiveIntegerArg(value: unknown): number {
  if (typeof value === "number" && Number.isSafeInteger(value) && value > 0) return value;
  throw new McpToolError("INVALID_ARGUMENT", "postId must be a positive integer");
}
function integerArg(value: unknown, fallback: number, maximum = 50): number {
  if (value === undefined) return fallback;
  if (typeof value !== "number" || !Number.isSafeInteger(value)) throw new McpToolError("INVALID_ARGUMENT", "numeric argument is invalid");
  return Math.min(maximum, Math.max(1, value));
}
function stringArg(value: unknown, fallback: string, maximum: number, required = false): string {
  if (value === undefined || value === null) {
    if (required) throw new McpToolError("INVALID_ARGUMENT", "required string argument is missing");
    return fallback;
  }
  if (typeof value !== "string" || (required && !value.trim()) || value.length > maximum) {
    throw new McpToolError("INVALID_ARGUMENT", "string argument is invalid");
  }
  return value;
}
function optionalString(value: unknown, maximum: number): string | null | undefined {
  if (value === undefined || value === null) return value;
  if (typeof value !== "string" || value.length > maximum) throw new McpToolError("INVALID_ARGUMENT", "string argument is invalid");
  return value;
}
function stringArray(value: unknown, maxItems: number, maxLength: number): string[] | null | undefined {
  if (value === undefined || value === null) return value;
  if (!Array.isArray(value) || value.length > maxItems || value.some((item) => typeof item !== "string" || item.length > maxLength)) {
    throw new McpToolError("INVALID_ARGUMENT", "string array argument is invalid");
  }
  return value as string[];
}
function readContent(args: Record<string, unknown>): string {
  if (args.content !== undefined && args.content !== null) return stringArg(args.content, "", 2 * 1024 * 1024, true);
  if (typeof args.contentBase64 !== "string" || args.contentBase64.length > 2_800_000) {
    throw new McpToolError("INVALID_ARGUMENT", "content or contentBase64 is required");
  }
  try {
    const bytes = Uint8Array.from(atob(args.contentBase64), (character) => character.charCodeAt(0));
    if (bytes.byteLength > 2 * 1024 * 1024) throw new Error("too large");
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch { throw new McpToolError("INVALID_ARGUMENT", "contentBase64 must contain bounded UTF-8 content"); }
}
function decodeOptionalBase64(value: unknown, field: string): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string" || value.length > 300_000) throw new McpToolError("INVALID_ARGUMENT", `${field} is invalid`);
  try { return new TextDecoder("utf-8", { fatal: true }).decode(Uint8Array.from(atob(value), (item) => item.charCodeAt(0))); }
  catch { throw new McpToolError("INVALID_ARGUMENT", `${field} must contain Base64 UTF-8 text`); }
}
function objectId(value: unknown): number {
  if (value && typeof value === "object" && Number.isSafeInteger((value as Record<string, unknown>).id)) {
    return (value as { id: number }).id;
  }
  throw new PlatformMcpPortError(502, "Content returned an invalid article");
}
function safeMessage(error: unknown): string {
  return error instanceof Error && error.message.length <= 300 ? error.message : "Publication failed";
}
