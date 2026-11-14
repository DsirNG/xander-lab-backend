import type { PresentationDeck } from "../interfaces/agent-presentation.js";
import { parsePresentationDeck, parsePresentationSlide, PresentationError } from "./agent-presentation-validation.js";

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new PresentationError(400, "PPT 参数必须是对象");
  return value as Record<string, unknown>;
}

function content(value: unknown, limit: number): string {
  if (typeof value !== "string" || !value.trim() || value.length > limit || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value)) {
    throw new PresentationError(400, "PPT 文字为空、过长或格式无效");
  }
  return value.trim();
}

/** Conservative SVG line budget: non-ASCII uses two units, never fewer than CJK glyphs. */
function fits(value: string, width: number, maxLines: number): boolean {
  let lines = 1, used = 0;
  for (const char of value) {
    const size = char.codePointAt(0)! <= 127 ? 1 : 2;
    if (char === "\n" || used + size > width) { lines++; used = 0; }
    if (char !== "\n") used += size;
    if (lines > maxLines) return false;
  }
  return true;
}

function fit(value: string, width: number, maxLines: number, maxLength: number): string {
  if (value.length <= maxLength && fits(value, width, maxLines)) return value;
  const chars = Array.from(value);
  while (chars.length) {
    chars.pop();
    const candidate = chars.join("").trimEnd() + "…";
    if (candidate.length <= maxLength && fits(candidate, width, maxLines)) return candidate;
  }
  return "…";
}

/** Fit model-planned slides before creating a task; keep every shortened detail in speaker notes. */
export function preparePresentationDeck(value: unknown): { deck: PresentationDeck; adjustedSlides: number } {
  const input = record(value);
  if (new TextEncoder().encode(JSON.stringify(input)).length > 128 * 1024) throw new PresentationError(413, "PPT 内容过大，请精简页面");
  if (!Array.isArray(input.slides) || !input.slides.length || input.slides.length > 20) throw new PresentationError(400, "PPT 需要 1 到 20 页");
  const english = input.language === "en-US";
  let adjustedSlides = 0;
  const slides = input.slides.map(value => {
    const raw = record(value);
    const originalTitle = content(raw.title, 300);
    const rawBullets = raw.bullets ?? [];
    if (!Array.isArray(rawBullets) || rawBullets.length > 20) throw new PresentationError(400, "PPT 单页内容过多，请重新组织大纲");
    let bullets = rawBullets.map(value => content(value, 600));
    // Models can add rich blocks while selecting a legacy text layout. Keep
    // the useful content and normalize it into readable entries instead of
    // rejecting an otherwise valid presentation.
    let requestedLayout = String(raw.layout ?? "bullets");
    const rawBlocks = Array.isArray(raw.blocks) ? raw.blocks : [];
    const rawChart = record(raw.chart ?? {});
    const chartPoints = Array.isArray(rawChart.points) ? rawChart.points : [];
    // A chart without source-backed data is a planning mistake, not a fatal
    // request error. Keep the page useful by rendering its narrative content
    // as editable bullets and leave the actual chart for a later revision.
    if (requestedLayout === "chart" && !chartPoints.length) requestedLayout = "bullets";
    const richLayout = ["cards", "process", "comparison"].includes(requestedLayout);
    const normalizedBlocks = !richLayout && rawBlocks.length > 0 && !bullets.length;
    if (normalizedBlocks) {
      bullets = rawBlocks.map(value => {
        const block = record(value);
        return `${content(block.heading, 32)}：${content(block.body, 100)}`;
      });
    }
    // Reuse strict validation for source links, notes and the requested layout.
    const normalizedRaw = {
      ...raw,
      title: "Title",
      layout: requestedLayout,
      chart: requestedLayout === "chart" ? raw.chart : undefined,
      bullets: [],
      blocks: richLayout ? raw.blocks : undefined,
    };
    const validated = parsePresentationSlide(normalizedRaw);
    // Rich layouts are authored as complete statements. Do not silently cut a
    // comparison or data conclusion into an ellipsis to make it fit.
    if (["cards", "process", "comparison", "chart", "closing"].includes(validated.layout)) {
      if (originalTitle.length > 70) throw new PresentationError(400, "请将页面标题改写为70字内的清晰结论");
      return parsePresentationSlide({ ...raw, title: originalTitle });
    }
    let layout = validated.layout;
    if ((layout === "cover" && bullets.length > 2) || (layout === "columns" && bullets.length > 4)) layout = "bullets";
    const capacity = layout === "cover" ? 2 : layout === "columns" ? 4 : 5;
    const title = fit(originalTitle, layout === "cover" ? 32 : 56, layout === "cover" ? 3 : 2, 70);
    const details: string[] = [];
    if (title !== originalTitle) details.push(`${english ? "Full title" : "完整标题"}: ${originalTitle}`);
    const visible = bullets.slice(0, capacity).map((bullet, block) => {
      const fitted = fit(bullet, layout === "cover" ? 106 : layout === "columns" ? 50 : 84, layout === "columns" ? 4 : layout === "cover" ? 1 : 2, 120);
      if (fitted !== bullet) details.push(`${block + 1}. ${bullet}`);
      return fitted;
    });
    for (let block = capacity; block < bullets.length; block++) details.push(`${block + 1}. ${bullets[block]}`);
    const notes = [validated.notes, details.length ? `${english ? "Full details" : "完整说明"}:\n${details.join("\n")}` : ""].filter(Boolean).join("\n\n");
    if (notes.length > 2000) throw new PresentationError(400, "PPT 单页内容无法在页面与备注中完整保留，请拆分内容");
    if (normalizedBlocks || layout !== validated.layout || title !== originalTitle || details.length) adjustedSlides++;
    return parsePresentationSlide({ ...validated, title, layout, bullets: visible, notes, blocks: undefined });
  });
  return { deck: parsePresentationDeck({ ...input, slides }), adjustedSlides };
}
