import type { PresentationElement, PresentationPalette } from "../interfaces/agent-presentation.js";
import { PresentationError } from "./agent-presentation-validation.js";

function invalid(): never { throw new PresentationError(400, "PPT 页面元素格式无效，请检查坐标、颜色和素材引用"); }
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return invalid();
  return value as Record<string, unknown>;
}
export function sceneColor(value: unknown): string {
  if (typeof value !== "string" || !/^#[a-fA-F0-9]{6}$/.test(value)) return invalid();
  return value;
}
function number(value: unknown, min: number, max: number): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max) return invalid();
  return value;
}
export function parsePalette(value: unknown): PresentationPalette {
  const item = record(value);
  return { background: sceneColor(item.background), foreground: sceneColor(item.foreground), muted: sceneColor(item.muted), accent: sceneColor(item.accent), surface: sceneColor(item.surface) };
}
export function parseElements(value: unknown): PresentationElement[] {
  if (!Array.isArray(value) || value.length > 200) return invalid();
  return value.map(raw => {
    const item = record(raw);
    if (!["text", "shape", "image", "svg"].includes(String(item.type))) return invalid();
    const result: PresentationElement = {
      type: item.type as PresentationElement["type"], x: number(item.x, 0, 1280), y: number(item.y, 0, 720),
      width: number(item.width, 1, 1280), height: number(item.height, 1, 720),
    };
    if (result.x + result.width > 1280.01 || result.y + result.height > 720.01) return invalid();
    for (const key of ["fill", "stroke", "color"] as const) if (item[key] !== undefined) result[key] = sceneColor(item[key]);
    for (const [key, min, max] of [["fontSize", 8, 160], ["strokeWidth", 0, 30], ["radius", 0, 360], ["opacity", 0, 1]] as const) {
      if (item[key] !== undefined) result[key] = number(item[key], min, max);
    }
    for (const [key, limit] of [["text", 4000], ["svg", 32000], ["alt", 300]] as const) {
      if (item[key] !== undefined) {
        if (typeof item[key] !== "string" || item[key].length > limit || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(item[key])) return invalid();
        result[key] = item[key];
      }
    }
    for (const key of ["attachmentId", "assetId"] as const) if (item[key] !== undefined) {
      result[key] = number(item[key], 1, Number.MAX_SAFE_INTEGER);
      if (!Number.isSafeInteger(result[key])) return invalid();
    }
    if (item.bold !== undefined) { if (typeof item.bold !== "boolean") return invalid(); result.bold = item.bold; }
    if (item.align !== undefined) {
      if (!["left", "center", "right"].includes(String(item.align))) return invalid();
      result.align = item.align as PresentationElement["align"];
    }
    if (item.shape !== undefined) {
      if (!["rect", "ellipse", "line"].includes(String(item.shape))) return invalid();
      result.shape = item.shape as PresentationElement["shape"];
    }
    if (item.fit !== undefined) {
      if (!["cover", "contain"].includes(String(item.fit))) return invalid();
      result.fit = item.fit as PresentationElement["fit"];
    }
    if (result.type === "text" && !result.text?.trim()) return invalid();
    if (result.type === "image" && Number(Boolean(result.attachmentId)) + Number(Boolean(result.assetId)) !== 1) return invalid();
    if (result.type === "svg" && !result.svg?.trim()) return invalid();
    return result;
  });
}
