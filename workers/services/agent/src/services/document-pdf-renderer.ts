// Pin the UMD build: this version's ESM build corrupts subset glyphs in Workers.
import fontkit from "@pdf-lib/fontkit/dist/fontkit.umd.js";
import { Buffer } from "buffer/index.js";
import { PDFDocument, PDFString, degrees, rgb, type PDFFont } from "pdf-lib";
import { AgentAttachmentError } from "./agent-attachment-service.js";
import type { DocumentBlock, DocumentSpan } from "./document-markdown.js";

type Fragment = DocumentSpan & { width: number };
type Line = Fragment[];
const WIDTH = 595.28, HEIGHT = 841.89, MARGIN = 48, BOTTOM = 52;
const INK = rgb(0.12, 0.16, 0.22), MUTED = rgb(0.39, 0.45, 0.53), BLUE = rgb(0.12, 0.32, 0.65);

/** Load a bundled, licensed font only for PDF requests; no filesystem or remote font service. */
export async function loadDocumentFont(): Promise<Uint8Array> {
  return new Uint8Array((await import("../assets/document-sans.ttf")).default);
}

function wrap(spans: DocumentSpan[], size: number, width: number, font: PDFFont, supported: Set<number>): Line[] {
  const lines: Line[] = [[]];
  let used = 0;
  const widths = new Map<string, number>();
  const measure = (text: string) => {
    let value = widths.get(text);
    if (value === undefined) { value = font.widthOfTextAtSize(text, size); widths.set(text, value); }
    return value;
  };
  const next = () => { lines.push([]); used = 0; };
  for (const span of spans) {
    // Keep unsupported symbols identifiable rather than emitting invisible missing glyphs.
    const text = Array.from(span.text.replace(/\t/g, "    ")).map(char => char === "\n" || supported.has(char.codePointAt(0)!)
      ? char : `[U+${char.codePointAt(0)!.toString(16).toUpperCase()}]`).join("");
    const pieces = text.match(/\n|[\x21-\x7e]+|[^\S\n]+|[^\x00-\x7f]/gu) ?? [];
    for (const piece of pieces) {
      if (piece === "\n") { next(); continue; }
      const pieceWidth = measure(piece);
      if (used && pieceWidth <= width && used + pieceWidth > width) next();
      for (const char of piece) {
        const charWidth = measure(char);
        if (used && used + charWidth > width) next();
        const line = lines[lines.length - 1];
        const last = line.at(-1);
        if (last && last.bold === span.bold && last.italic === span.italic && last.code === span.code && last.strike === span.strike && last.link === span.link) {
          last.text += char; last.width += charWidth;
        } else line.push({ ...span, text: char, width: charWidth });
        used += charWidth;
      }
    }
  }
  return lines;
}

export async function renderPdf(blocks: DocumentBlock[], fontBytes: Uint8Array): Promise<Uint8Array> {
  const pdf = await PDFDocument.create();
  pdf.setCreator("DinQorAI");
  pdf.registerFontkit(fontkit);
  // fontkit's TTF subsetter requires Buffer's view-based slice semantics.
  // Plain Uint8Array (the Workers Data module type) silently corrupts outlines.
  const font = await pdf.embedFont(Buffer.from(fontBytes), { subset: true });
  const supported = new Set(font.getCharacterSet());
  let page = pdf.addPage([WIDTH, HEIGHT]);
  let y = HEIGHT - MARGIN;
  const freshPage = () => {
    if (pdf.getPageCount() >= 250) throw new AgentAttachmentError("文档超过250页，请拆分导出", 413);
    page = pdf.addPage([WIDTH, HEIGHT]); y = HEIGHT - MARGIN;
  };
  const ensure = (height: number) => { if (y - height < BOTTOM) freshPage(); };
  const drawLine = (line: Line, x: number, baseline: number, size: number) => {
    for (const part of line) {
      const color = part.link ? BLUE : INK;
      if (part.code) page.drawRectangle({ x, y: baseline - 3, width: part.width, height: size + 6, color: rgb(0.94, 0.96, 0.98) });
      page.drawText(part.text, { x, y: baseline, font, size, color, ySkew: part.italic ? degrees(10) : undefined });
      // A tiny second pass supplies emphasis while sharing one embedded CJK font subset.
      if (part.bold) page.drawText(part.text, { x: x + 0.25, y: baseline, font, size, color });
      if (part.link || part.strike) page.drawLine({ start: { x, y: baseline + (part.strike ? size * 0.35 : -2) }, end: { x: x + part.width, y: baseline + (part.strike ? size * 0.35 : -2) }, thickness: 0.5, color });
      if (part.link) {
        page.node.addAnnot(pdf.context.register(pdf.context.obj({ Type: "Annot", Subtype: "Link", Rect: [x, baseline - 3, x + part.width, baseline + size], Border: [0, 0, 0],
          A: { Type: "Action", S: "URI", URI: PDFString.of(part.link) } })));
      }
      x += part.width;
    }
  };
  const counts = new Map<number, number>();
  for (const block of blocks) {
    if (block.type === "rule") {
      ensure(20); page.drawLine({ start: { x: MARGIN, y: y - 8 }, end: { x: WIDTH - MARGIN, y: y - 8 }, thickness: 0.7, color: MUTED }); y -= 20; continue;
    }
    if (block.type === "table") {
      const columns = block.rows[0]?.length ?? 0;
      if (!columns) continue;
      if (columns > 12) throw new AgentAttachmentError("PDF表格最多支持12列，请拆分表格");
      const cellWidth = (WIDTH - MARGIN * 2) / columns, size = 9, lineHeight = 15, pad = 6;
      const rows = block.rows.map((row, index) => Array.from({ length: columns }, (_, col) => wrap((row[col] ?? []).map(span => ({ ...span, bold: index === 0 || span.bold })), size, cellWidth - pad * 2, font, supported)));
      const drawRow = (cells: Line[][], offset: number, count: number, header: boolean) => {
        const height = count * lineHeight + pad * 2;
        cells.forEach((lines, col) => {
          page.drawRectangle({ x: MARGIN + col * cellWidth, y: y - height, width: cellWidth, height, borderWidth: 0.5, borderColor: rgb(0.78, 0.82, 0.87), color: header ? rgb(0.91, 0.94, 0.97) : rgb(1, 1, 1) });
          for (let i = 0; i < count; i++) drawLine(lines[offset + i] ?? [], MARGIN + col * cellWidth + pad, y - pad - size - i * lineHeight, size);
        });
        y -= height;
      };
      const header = rows[0], headerLines = Math.max(...header.map(cell => cell.length));
      if (headerLines * lineHeight + pad * 2 > (HEIGHT - MARGIN - BOTTOM) / 3) throw new AgentAttachmentError("PDF表头过长，请缩短表头");
      for (let r = 0; r < rows.length; r++) {
        const cells = rows[r], total = Math.max(...cells.map(cell => cell.length));
        let offset = 0;
        const fullHeight = total * lineHeight + pad * 2;
        if (fullHeight <= HEIGHT - MARGIN - BOTTOM - (headerLines * lineHeight + pad * 2) && y - fullHeight < BOTTOM) {
          freshPage(); if (r > 0) drawRow(header, 0, headerLines, true);
        }
        while (offset < total) {
          let available = Math.floor((y - BOTTOM - pad * 2) / lineHeight);
          if (available < 1) { freshPage(); if (r > 0) drawRow(header, 0, headerLines, true); available = Math.floor((y - BOTTOM - pad * 2) / lineHeight); }
          const count = Math.min(total - offset, available);
          drawRow(cells, offset, count, r === 0); offset += count;
        }
      }
      y -= 12; continue;
    }
    const size = block.heading ? [23, 18, 15, 13, 12, 11][block.heading - 1] : block.code ? 9 : 11;
    const lineHeight = size * 1.65;
    const indent = (block.level === undefined ? 0 : (Math.min(block.level, 8) + 1) * 16) + (block.quote ? 14 : 0);
    const x = MARGIN + indent + (block.code ? 8 : 0);
    const spans = block.spans.map(span => ({ ...span, bold: Boolean(block.heading) || span.bold }));
    const lines = wrap(spans, size, WIDTH - MARGIN - x - (block.code ? 8 : 0), font, supported);
    ensure(lineHeight + (block.heading ? 30 : 0));
    if (block.heading) y -= 8;
    let prefix = "";
    if (block.list) {
      const count = counts.get(block.list.id) ?? block.list.start;
      prefix = block.list.ordered ? `${count}.` : "•";
      counts.set(block.list.id, count + 1);
    }
    lines.forEach((line, index) => {
      ensure(lineHeight);
      if (block.code) page.drawRectangle({ x: MARGIN + indent, y: y - lineHeight, width: WIDTH - MARGIN * 2 - indent, height: lineHeight, color: rgb(0.94, 0.96, 0.98) });
      if (block.quote) page.drawLine({ start: { x: x - 8, y }, end: { x: x - 8, y: y - lineHeight }, thickness: 2, color: MUTED });
      if (index === 0 && prefix) page.drawText(prefix, { x: x - font.widthOfTextAtSize(prefix, size) - 5, y: y - size, font, size, color: INK });
      drawLine(line, x, y - size, size); y -= lineHeight;
    });
    y -= block.heading ? 6 : 8;
  }
  pdf.getPages().forEach((item, i) => {
    const label = `${i + 1} / ${pdf.getPageCount()}`;
    item.drawText(label, { x: (WIDTH - font.widthOfTextAtSize(label, 9)) / 2, y: 26, font, size: 9, color: MUTED });
  });
  return pdf.save();
}
