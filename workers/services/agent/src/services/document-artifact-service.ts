import type { AgentAttachmentReference } from "../interfaces/agent-attachment.js";
import { AgentAttachmentService } from "./agent-attachment-service.js";
import { parseDocumentMarkdown } from "./document-markdown.js";
import { renderWord } from "./document-word-renderer.js";
import { loadDocumentFont, renderPdf } from "./document-pdf-renderer.js";

/** Generate complete, structured documents before publishing their attachment metadata. */
export class DocumentArtifactService {
  constructor(private readonly attachments: AgentAttachmentService, private readonly fontLoader = loadDocumentFont) {}

  async create(userId: number, name: string, format: "docx" | "pdf", content: string): Promise<AgentAttachmentReference> {
    const blocks = parseDocumentMarkdown(content);
    const bytes = format === "docx" ? await renderWord(blocks) : await renderPdf(blocks, await this.fontLoader());
    const filename = `${name.replace(/\.[^./\\]+$/, "")}.${format}`;
    return this.attachments.upload(userId, { name: filename,
      type: format === "docx" ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/pdf",
      size: bytes.byteLength, bytes });
  }
}
