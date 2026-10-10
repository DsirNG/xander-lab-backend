export type OwnedMaterialResult = "owned" | "not_found" | "unavailable";

export interface BlogPlanMaterialPort {
  checkOwned(userId: number, materialId: number): Promise<OwnedMaterialResult>;
  getOwned(userId: number, materialId: number): Promise<{ title: string; content: string } | null>;
}
