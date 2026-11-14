import { AlignmentType, BorderStyle, Document, ExternalHyperlink, HeadingLevel, LevelFormat, Packer, Paragraph, ShadingType, Table, TableCell, TableRow, TextRun, WidthType } from "docx";
import type { DocumentBlock, DocumentSpan } from "./document-markdown.js";

const headings = [HeadingLevel.HEADING_1, HeadingLevel.HEADING_2, HeadingLevel.HEADING_3, HeadingLevel.HEADING_4, HeadingLevel.HEADING_5, HeadingLevel.HEADING_6];
function runs(spans: DocumentSpan[]): (TextRun | ExternalHyperlink)[] {
  return spans.flatMap(span => span.text.split("\n").map((text, index) => {
    const run = new TextRun({ text, break: index ? 1 : undefined, bold: span.bold, italics: span.italic, strike: span.strike,
      font: span.code ? "Consolas" : undefined, shading: span.code ? { fill: "F1F5F9", type: ShadingType.CLEAR } : undefined,
      style: span.link ? "Hyperlink" : undefined });
    return span.link ? new ExternalHyperlink({ link: span.link, children: [run] }) : run;
  }));
}

export async function renderWord(blocks: DocumentBlock[]): Promise<Uint8Array> {
  const lists = new Map<number, { ordered: boolean; start: number }>();
  const children = blocks.map(block => {
    if (block.type === "rule") return new Paragraph({ border: { bottom: { color: "CBD5E1", style: BorderStyle.SINGLE, size: 6 } } });
    if (block.type === "table") return new Table({ width: { size: 100, type: WidthType.PERCENTAGE },
      rows: block.rows.map((row, i) => new TableRow({ tableHeader: i === 0, children: row.map(cell => new TableCell({
        shading: i === 0 ? { fill: "E8EEF5" } : undefined,
        children: [new Paragraph({ children: runs(cell.map(span => ({ ...span, bold: i === 0 || span.bold }))) })],
      })) })),
    });
    if (block.list) lists.set(block.list.id, block.list);
    const level = Math.min(block.level ?? 0, 8);
    return new Paragraph({
      children: runs(block.spans), heading: block.heading ? headings[block.heading - 1] : undefined,
      numbering: block.list ? { reference: `list-${block.list.id}`, level } : undefined,
      indent: block.quote || (!block.list && block.level !== undefined) ? { left: block.quote ? 360 : (level + 1) * 360 } : undefined,
      border: block.quote ? { left: { color: "94A3B8", style: BorderStyle.SINGLE, size: 18, space: 10 } } : undefined,
      shading: block.code ? { fill: "F1F5F9" } : undefined,
      spacing: { after: 160, line: 320 }, keepNext: Boolean(block.heading),
    });
  });
  const doc = new Document({
    creator: "DinQorAI", styles: { default: { document: { run: { font: "Microsoft YaHei", size: 22, color: "1E293B" }, paragraph: { spacing: { after: 160, line: 320 } } } } },
    numbering: { config: [...lists].map(([id, list]) => ({ reference: `list-${id}`, levels: Array.from({ length: 9 }, (_, level) => ({
      level, format: list.ordered ? LevelFormat.DECIMAL : LevelFormat.BULLET,
      text: list.ordered ? `%${level + 1}.` : "•", start: list.start, alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: (level + 1) * 360, hanging: 260 } } },
    })) })) },
    sections: [{ properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 1134, bottom: 1134, left: 1134, right: 1134 } } }, children }],
  });
  return new Uint8Array(await Packer.toArrayBuffer(doc));
}
