import type { PresentationDeck, PresentationSlide } from "../interfaces/agent-presentation.js";
import { parseElements, parsePalette, sceneColor } from "./agent-presentation-scene-validation.js";

export class PresentationError extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = "PresentationError"; }
}
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new PresentationError(400, "PPT 参数必须是对象");
  return value as Record<string, unknown>;
}
function text(value: unknown, max: number, optional = false): string {
  if (optional && value === undefined) return "";
  if (typeof value !== "string" || (!optional && !value.trim()) || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value)) {
    throw new PresentationError(400, "PPT 文字为空、过长或格式无效");
  }
  return value.trim();
}
export function parsePresentationSlide(value: unknown): PresentationSlide {
  const input = object(value);
  const layout = input.layout ?? "bullets";
  if (!["canvas", "cover", "bullets", "columns", "cards", "process", "comparison", "chart", "closing"].includes(String(layout))) throw new PresentationError(400, "PPT 页面布局无效");
  const bullets = input.bullets ?? [];
  if (!Array.isArray(bullets)) throw new PresentationError(400, "PPT 页面要点格式无效");
  const sources = input.sources ?? [];
  if (!Array.isArray(sources)) throw new PresentationError(400, "PPT 来源格式无效");
  const rich: Partial<PresentationSlide> = {};
  if (input.background !== undefined) rich.background = sceneColor(input.background);
  if (input.elements !== undefined) rich.elements = parseElements(input.elements);
  for (const [key, max] of [["kicker", 28], ["subtitle", 80], ["takeaway", 100]] as const) {
    if (input[key] !== undefined) rich[key] = text(input[key], max, true);
  }
  if (input.blocks !== undefined) {
    if (!Array.isArray(input.blocks)) throw new PresentationError(400, "PPT 内容模块格式无效");
    rich.blocks = input.blocks.map(value => {
      const block = object(value);
      return { heading: text(block.heading, 32), body: text(block.body, 100) };
    });
  }
  if (input.chart !== undefined) {
    const chart = object(input.chart);
    if (!Array.isArray(chart.points)) throw new PresentationError(400, "图表数据格式无效");
    rich.chart = { unit: text(chart.unit, 12, true), caption: text(chart.caption, 100, true), points: chart.points.map(value => {
      const point = object(value);
      if (typeof point.value !== "number" || !Number.isFinite(point.value) || point.value < 0 || point.value > 1e12) throw new PresentationError(400, "图表数据必须是有效非负数");
      return { label: text(point.label, 24), value: point.value };
    }) };
  }
  return { ...rich, title: text(input.title, 70), layout: layout as PresentationSlide["layout"],
    bullets: bullets.map(bullet => text(bullet, 120)), notes: text(input.notes, 2000, true),
    sources: sources.map(value => {
      const source = object(value);
      const url = text(source.url, 2000);
      try {
        const parsed = new URL(url);
        if (!["https:", "http:"].includes(parsed.protocol) || parsed.username || parsed.password) throw new Error();
      } catch { throw new PresentationError(400, "PPT 来源链接无效"); }
      return { title: text(source.title, 120), url };
    }),
  };
}
export function parsePresentationDeck(value: unknown): PresentationDeck {
  const input = object(value);
  if (!Array.isArray(input.slides) || input.slides.length < 1 || input.slides.length > 20) throw new PresentationError(400, "PPT 需要 1 到 20 页");
  const language = input.language ?? "zh-CN", theme = input.theme ?? "navy";
  if (!["zh-CN", "en-US"].includes(String(language)) || !["navy", "paper"].includes(String(theme))) throw new PresentationError(400, "PPT 语言或主题无效");
  const deck: PresentationDeck = { title: text(input.title, 100), language: language as PresentationDeck["language"],
    theme: theme as PresentationDeck["theme"], slides: input.slides.map(parsePresentationSlide) };
  if (input.palette !== undefined) deck.palette = parsePalette(input.palette);
  if (new TextEncoder().encode(JSON.stringify(deck)).length > 512 * 1024) throw new PresentationError(413, "PPT 内容超过安全大小限制");
  return deck;
}
