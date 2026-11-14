import type { AgentAttachmentReference } from "./agent-attachment.js";
import type { AgentImageTaskNamespace, AgentImageTaskStorage } from "./agent-image-task.js";

export interface PresentationSource { title: string; url: string }
export interface PresentationBlock { heading: string; body: string }
export interface PresentationChart {
  unit: string;
  caption: string;
  points: { label: string; value: number }[];
}
export interface PresentationSlide {
  title: string;
  layout: "cover" | "bullets" | "columns" | "cards" | "process" | "comparison" | "chart" | "closing";
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
  files?: { pptx: AgentAttachmentReference; source: AgentAttachmentReference; previews: AgentAttachmentReference[] };
}
export interface PresentationRenderer {
  render(deck: PresentationDeck): Promise<{ pptx: Uint8Array; source: Uint8Array; previews: Uint8Array[] }>;
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
