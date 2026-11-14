import { Lexer, type Token, type Tokens } from "marked";
import { AgentAttachmentError } from "./agent-attachment-service.js";

export interface DocumentSpan {
  text: string;
  bold?: boolean;
  italic?: boolean;
  code?: boolean;
  strike?: boolean;
  link?: string;
}
export interface DocumentParagraph {
  type: "paragraph";
  spans: DocumentSpan[];
  heading?: number;
  quote?: boolean;
  code?: boolean;
  level?: number;
  list?: { id: number; ordered: boolean; start: number };
}
export type DocumentBlock = DocumentParagraph | { type: "table"; rows: DocumentSpan[][][] } | { type: "rule" };

const decode = (text: string) => text.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (all, entity: string) => {
  if (entity[0] !== "#") return ({ amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " } as Record<string, string>)[entity.toLowerCase()] ?? all;
  const value = entity[1].toLowerCase() === "x" ? parseInt(entity.slice(2), 16) : parseInt(entity.slice(1), 10);
  return value > 0 && value <= 0x10ffff && !(value >= 0xd800 && value <= 0xdfff) ? String.fromCodePoint(value) : "\uFFFD";
});

function safeLink(value: string): string | undefined {
  try { return /^(https?:|mailto:)$/.test(new URL(value).protocol) ? value : undefined; } catch { return undefined; }
}

/** Parse structure once so Word and PDF have identical content, without executing HTML. */
export function parseDocumentMarkdown(source: string): DocumentBlock[] {
  if (!source.trim()) throw new AgentAttachmentError("文档内容不能为空");
  if (source.length > 200_000) throw new AgentAttachmentError("文档内容不能超过200000字符，请拆分导出", 413);
  const lexer = new Lexer({ gfm: true });
  const tokens = lexer.lex(source.replace(/\r\n?/g, "\n").replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, ""));
  const inline = (items: Token[], style: Omit<DocumentSpan, "text"> = {}, depth = 0): DocumentSpan[] => {
    if (depth > 32) throw new AgentAttachmentError("文档嵌套层级过深");
    return items.flatMap((token): DocumentSpan[] => {
      switch (token.type) {
        case "strong": return inline(token.tokens ?? [], { ...style, bold: true }, depth + 1);
        case "em": return inline(token.tokens ?? [], { ...style, italic: true }, depth + 1);
        case "del": return inline(token.tokens ?? [], { ...style, strike: true }, depth + 1);
        case "link": return inline(token.tokens ?? [], { ...style, link: safeLink(token.href) }, depth + 1);
        // Preserve image references without fetching arbitrary user-controlled URLs on the Worker.
        case "image": return [{ ...style, text: `${decode(token.text || "Image")} (${token.href})`, link: safeLink(token.href) }];
        case "codespan": return [{ ...style, text: token.text, code: true }];
        case "br": return [{ ...style, text: "\n" }];
        case "html": return /^<br\s*\/?\s*>$/i.test(token.raw) ? [{ ...style, text: "\n" }] : [{ ...style, text: token.raw }];
        default: return "tokens" in token && token.tokens ? inline(token.tokens ?? [], style, depth + 1)
          : [{ ...style, text: decode("text" in token ? token.text : token.raw) }];
      }
    });
  };
  let listId = 0;
  const walk = (items: Token[], context: { quote?: boolean; level?: number } = {}, depth = 0): DocumentBlock[] => {
    if (depth > 32) throw new AgentAttachmentError("文档嵌套层级过深");
    const blocks: DocumentBlock[] = [];
    for (const token of items) {
      switch (token.type) {
        case "space": case "def": break;
        case "heading": blocks.push({ type: "paragraph", ...context, heading: token.depth, spans: inline(token.tokens ?? []) }); break;
        case "paragraph": case "text": blocks.push({ type: "paragraph", ...context, spans: inline(token.tokens ?? lexer.inlineTokens(token.text)) }); break;
        case "code": blocks.push({ type: "paragraph", ...context, code: true, spans: [{ text: token.text, code: true }] }); break;
        case "blockquote": blocks.push(...walk(token.tokens ?? [], { ...context, quote: true }, depth + 1)); break;
        case "hr": blocks.push({ type: "rule" }); break;
        case "table": {
          const table = token as Tokens.Table;
          blocks.push({ type: "table", rows: [table.header, ...table.rows].map(row => row.map(cell => inline(cell.tokens))) });
          break;
        }
        case "list": {
          const id = ++listId;
          for (const item of token.items as Tokens.ListItem[]) {
            const children = walk(item.tokens, { ...context, level: (context.level ?? -1) + 1 }, depth + 1);
            const first = children[0];
            if (first?.type === "paragraph") {
              first.list = { id, ordered: Boolean(token.ordered), start: Number(token.start) || 1 };
              if (item.task) first.spans.unshift({ text: item.checked ? "[x] " : "[ ] " });
            }
            blocks.push(...children);
          }
          break;
        }
        default: blocks.push({ type: "paragraph", ...context, spans: [{ text: token.raw }] });
      }
    }
    return blocks;
  };
  return walk(tokens);
}
