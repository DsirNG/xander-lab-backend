import type { AgentAttachmentReference } from "../interfaces/agent-attachment.js";
import { AgentAttachmentService } from "./agent-attachment-service.js";

const encoder = new TextEncoder();
const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let i = 0; i < 8; i++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc32 = (data: Uint8Array) => { let c = 0xffffffff; for (const b of data) c = crcTable[(c ^ b) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const u16 = (n: number) => new Uint8Array([n & 255, n >>> 8 & 255]);
const u32 = (n: number) => new Uint8Array([n & 255, n >>> 8 & 255, n >>> 16 & 255, n >>> 24 & 255]);
const concat = (parts: Uint8Array[]) => { const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0)); let offset = 0; for (const p of parts) { out.set(p, offset); offset += p.length; } return out; };
const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

function zip(files: Array<[string, string]>): Uint8Array {
  const local: Uint8Array[] = []; const central: Uint8Array[] = []; let offset = 0;
  for (const [name, value] of files) { const n = encoder.encode(name); const d = encoder.encode(value); const crc = crc32(d);
    const localHeader = concat([new Uint8Array([80,75,3,4,20,0,0,0,0,0,0,0,0,0]), u32(crc), u32(d.length), u32(d.length), u16(n.length), u16(0)]);
    local.push(concat([localHeader, n, d]));
    central.push(concat([new Uint8Array([80,75,1,2,20,0,20,0,0,0,0,0,0,0,0,0]), u32(crc), u32(d.length), u32(d.length), u16(n.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(offset), n]));
    offset += localHeader.length + n.length + d.length;
  }
  const body = concat(local); const directory = concat(central);
  return concat([body, directory, concat([new Uint8Array([80,75,5,6,0,0,0,0]), u16(files.length), u16(files.length), u32(directory.length), u32(body.length), u16(0)])]);
}

function docx(text: string): Uint8Array {
  const body = text.split(/\r?\n/).map(line => `<w:p><w:r><w:t xml:space="preserve">${xml(line || " ")}</w:t></w:r></w:p>`).join("");
  return zip([
    ["[Content_Types].xml", `<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>`],
    ["_rels/.rels", `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>`],
    ["word/document.xml", `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}<w:sectPr/></w:body></w:document>`],
  ]);
}

function pdf(text: string): Uint8Array {
  const hex = (s: string) => Array.from(s).map(ch => { const code = ch.codePointAt(0) ?? 32; return `${(code >>> 8).toString(16).padStart(2, "0")}${(code & 255).toString(16).padStart(2, "0")}`; }).join("");
  const lines = text.split(/\r?\n/).slice(0, 100); const stream = ["BT", "/F1 11 Tf", "50 780 Td", ...lines.map((line, i) => `${i ? "0 -16 Td " : ""}<${hex(line.slice(0, 110))}> Tj`), "ET"] .join("\n");
  const objects = [`<< /Type /Catalog /Pages 2 0 R >>`, `<< /Type /Pages /Kids [3 0 R] /Count 1 >>`, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>`, `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`, `<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H /DescendantFonts [6 0 R] >>`, `<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 4 >> /DW 1000 >>`];
  let output = "%PDF-1.4\n"; const offsets = [0]; for (let i = 0; i < objects.length; i++) { offsets.push(output.length); output += `${i + 1} 0 obj\n${objects[i]}\nendobj\n`; } const xref = output.length; output += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.slice(1).map(n => `${String(n).padStart(10, "0")} 00000 n `).join("\n")}\ntrailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`; return encoder.encode(output);
}

export class DocumentArtifactService {
  constructor(private readonly attachments: AgentAttachmentService) {}
  async create(userId: number, name: string, format: "docx" | "pdf", content: string): Promise<AgentAttachmentReference> {
    const extension = format; const bytes = format === "docx" ? docx(content) : pdf(content);
    return this.attachments.upload(userId, { name: name.toLowerCase().endsWith(`.${extension}`) ? name : `${name}.${extension}`, type: format === "docx" ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/pdf", size: bytes.byteLength, bytes });
  }
}
