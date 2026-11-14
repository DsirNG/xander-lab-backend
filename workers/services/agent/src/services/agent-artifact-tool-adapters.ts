import type { DocumentArtifactService } from "./document-artifact-service.js";
import { AgentToolExecutionError, type AgentTool } from "./agent-tool-runtime.js";
import { buildArtifactCard } from "./agent-run-service.js";

export function createArtifactTool(documents: DocumentArtifactService | undefined, userId: number, conversationId: number, runVersion: number): AgentTool {
  let delivery = 0;
  return {
    definition: () => ({ name: "emit_artifact", description: "创建文件交付产物", parameters: {}, readOnly: false }),
    execute: async (_context, input) => {
      let { artifact, fileCount } = buildArtifactCard(input, conversationId, runVersion);
      const documentArtifact = artifact as { files: Array<{ path: string; content: string; format?: "docx" | "pdf" }> };
      const files = [];
      for (const source of documentArtifact.files) {
        if (!source.format) { files.push(source); continue; }
        if (!documents) throw new AgentToolExecutionError("DOCUMENT_SERVICE_UNAVAILABLE", "文档服务尚未配置");
        const uploaded = await documents.create(userId, source.path, source.format, source.content);
        files.push({ ...source, language: "", content: "", contentType: uploaded.contentType, url: uploaded.url, size: uploaded.size, attachmentId: uploaded.id });
      }
      artifact = { ...artifact, id: ++delivery === 1 ? artifact.id : `${artifact.id}-${delivery}`, files };
      return { ok: true, data: { fileCount, artifact, hint: "文件已经作为交付卡发给用户了，用户可以下载文件。" } };
    },
  };
}
