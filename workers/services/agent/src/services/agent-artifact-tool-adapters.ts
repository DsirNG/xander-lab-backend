import type { DocumentArtifactService } from "./document-artifact-service.js";
import type { AgentTool } from "./agent-tool-runtime.js";
import { buildArtifactCard } from "./agent-run-service.js";

export function createArtifactTool(documents: DocumentArtifactService | undefined, userId: number, conversationId: number, runVersion: number): AgentTool {
  return {
    definition: () => ({ name: "emit_artifact", description: "创建文件交付产物", parameters: {}, readOnly: false }),
    execute: async (_context, input) => {
      let { artifact, fileCount } = buildArtifactCard(input, conversationId, runVersion);
      const documentArtifact = artifact as { kind?: string; format?: string; files: Array<{ path: string; content: string; contentType?: string }> };
      if (documents && documentArtifact.kind === "document"
        && (documentArtifact.format === "docx" || documentArtifact.format === "pdf")
        && documentArtifact.files.length === 1) {
        const source = documentArtifact.files[0];
        const uploaded = await documents.create(userId, source.path, documentArtifact.format as "docx" | "pdf", source.content);
        artifact = { ...artifact, files: [{ ...source, content: "", contentType: uploaded.contentType, url: uploaded.url, size: uploaded.size, attachmentId: uploaded.id }] };
      }
      return { ok: true, data: { fileCount, artifact, hint: "代码已经作为交付卡发给用户了，用户可以复制或下载文件。" } };
    },
  };
}
