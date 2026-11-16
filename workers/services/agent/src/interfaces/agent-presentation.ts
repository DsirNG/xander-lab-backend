import type { AgentAttachmentReference } from "./agent-attachment.js";
import type { AgentImageTaskNamespace, AgentImageTaskStorage } from "./agent-image-task.js";

export interface PresentationSource { title: string; url: string }
export interface PresentationBlock { heading: string; body: string }
export interface PresentationChart {
  unit: string;
  caption: string;
  points: { label: string; value: number }[];
}
/** One 1280 × 720 scene is consumed by both preview and editable export. */
export interface PresentationElement {
  type: "text" | "shape" | "image" | "svg";
  x: number; y: number; width: number; height: number;
  text?: string; fontSize?: number; fontFamily?: "Noto Sans CJK SC" | "Noto Serif CJK SC" | "Noto Sans Mono CJK SC"; color?: string; bold?: boolean;
  align?: "left" | "center" | "right";
  fill?: string; stroke?: string; strokeWidth?: number;
  shape?: "rect" | "ellipse" | "line"; radius?: number; opacity?: number;
  attachmentId?: number; assetId?: number; svg?: string; alt?: string;
  fit?: "cover" | "contain";
}
export interface PresentationPalette { background: string; foreground: string; muted: string; accent: string; surface: string }
export interface PresentationAsset { key: string; contentType: string; data: string }
export interface PresentationAssetReference { key: string; url: string; contentType: string }
export interface PresentationAssetPort {
  load(userId: number, deck: PresentationDeck): Promise<PresentationAsset[]>;
  references?(userId: number, deck: PresentationDeck): Promise<PresentationAssetReference[]>;
}
export interface PresentationSlide {
  title: string;
  layout: "canvas" | "cover" | "bullets" | "columns" | "cards" | "process" | "comparison" | "chart" | "closing";
  background?: string;
  elements?: PresentationElement[];
  kicker?: string;
  subtitle?: string;
  takeaway?: string;
  blocks?: PresentationBlock[];
  chart?: PresentationChart;
  bullets: string[];
  notes: string;
  sources: PresentationSource[];
}
export interface PresentationDeck {
  title: string;
  language: "zh-CN" | "en-US";
  theme: "navy" | "paper";
  palette?: PresentationPalette;
  slides: PresentationSlide[];
}
export type PresentationStatus = "queued" | "rendering" | "uploading" | "completed" | "failed" | "cancelled";
export interface PresentationVersion {
  version: number;
  attempt: number;
  status: PresentationStatus;
  deck: PresentationDeck;
  createdAt: string;
  error?: string;
  files?: { pptx: number; source: number; previews: number[] };
}
export interface PresentationJob { id: string; userId: number; conversationId: number; version: number }
export interface PresentationView extends Omit<PresentationVersion, "files" | "attempt"> {
  id: string;
  conversationId: number;
  currentVersion: number;
  history: { version: number; status: PresentationStatus }[];
  assets?: PresentationAssetReference[];
  files?: { pptx: AgentAttachmentReference; source: AgentAttachmentReference; previews: AgentAttachmentReference[] };
}
export interface PresentationRenderer {
  render(deck: PresentationDeck, assets?: PresentationAsset[]): Promise<{ pptx: Uint8Array; source: Uint8Array; previews: Uint8Array[] }>;
}
export interface PresentationAttachmentPort {
  upload(userId: number, file: { name: string; type: string; size: number; bytes: Uint8Array }): Promise<AgentAttachmentReference>;
  resolveOwned(userId: number, ids: unknown[]): Promise<AgentAttachmentReference[]>;
}
export interface PresentationStorage extends AgentImageTaskStorage {
  transaction?<T>(callback: (storage: PresentationStorage) => Promise<T>): Promise<T>;
}
export type PresentationNamespace = AgentImageTaskNamespace;
export interface PresentationPort {
  create(userId: number, conversationId: number, key: string, input: unknown): Promise<PresentationView>;
  action(userId: number, id: string, action: string, input?: Record<string, unknown>): Promise<PresentationView>;
}
