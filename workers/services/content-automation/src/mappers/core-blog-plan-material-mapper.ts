import type { BlogPlanMaterialPort, OwnedMaterialResult } from "../interfaces/blog-plan-material-port.js";
import { fetchWithServiceBinding, type WorkerServiceBinding } from "@dinqor/worker-runtime";

export interface CoreBlogPlanMaterialEnvironment {
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: WorkerServiceBinding;
}

export class CoreBlogPlanMaterialMapper implements BlogPlanMaterialPort {
  constructor(private readonly env: CoreBlogPlanMaterialEnvironment) {}

  async checkOwned(userId: number, materialId: number): Promise<OwnedMaterialResult> {
    try {
      const material = await this.getOwned(userId, materialId);
      return material ? "owned" : "not_found";
    } catch {
      return "unavailable";
    }
  }

  async getOwned(userId: number, materialId: number): Promise<{ title: string; content: string } | null> {
    if (!this.env.CORE_SERVICE_URL || !this.env.CORE_SERVICE_TOKEN) throw new Error("Core material service is not configured");
    try {
      const response = await fetchWithServiceBinding(this.env.CORE_SERVICE,
        new URL("/internal/agent/recitation/material/get", this.env.CORE_SERVICE_URL), {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-dinqor-service-token": this.env.CORE_SERVICE_TOKEN,
        },
        body: JSON.stringify({ userId, materialId }),
        signal: AbortSignal.timeout(5_000),
      });
      if (response.status === 404) return null;
      if (!response.ok) throw new Error("Core material lookup failed");
      const payload: unknown = await response.json();
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
      const row = payload as Record<string, unknown>;
      if (row.id !== materialId || typeof row.title !== "string" || typeof row.content !== "string") return null;
      return { title: row.title, content: row.content };
    } catch {
      throw new Error("Core material lookup failed");
    }
  }
}
