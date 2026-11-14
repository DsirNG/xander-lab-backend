import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { inflateRawSync } from "node:zlib";
import { PDFDocument, PDFName, decodePDFRawStream } from "pdf-lib";
import fontkit from "@pdf-lib/fontkit";
import { DocumentArtifactService } from "../.test-build/services/document-artifact-service.js";
import { parseDocumentMarkdown } from "../.test-build/services/document-markdown.js";

// Match Workers Data modules, not Node Buffer; their slice semantics differ.
const font = async () => new Uint8Array(await readFile(new URL("../src/assets/document-sans.ttf", import.meta.url)));
const sample = `# 中文博客导出验证

这是 **加粗内容** 和 *斜体内容*，以及 [参考链接](https://example.com/blog)。

## 列表与引用

3. 第三项
4. 第四项
   - 嵌套项目

> 引用中的中文内容

| 功能 | 说明 |
| --- | --- |
| Word | 保留标题和表格 |
| PDF | 支持中文和分页 |

\`\`\`js
const message = "中文代码";
console.log(message);
\`\`\`

末尾标记END_OF_DOCUMENT
`;
function harness() {
  const uploads = [];
  const service = new DocumentArtifactService({ async upload(userId, file) { uploads.push({ userId, ...file }); return { id: 7, name: file.name, contentType: file.type, size: file.size, url: "https://example.com/document" }; } }, font);
  return { uploads, service };
}
function unzip(bytes) {
  const buffer = Buffer.from(bytes), files = {};
  for (let offset = 0; offset < buffer.length - 46; offset++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) continue;
    const method = buffer.readUInt16LE(offset + 10), size = buffer.readUInt32LE(offset + 20), nameLength = buffer.readUInt16LE(offset + 28);
    const name = buffer.subarray(offset + 46, offset + 46 + nameLength).toString();
    const local = buffer.readUInt32LE(offset + 42), start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
    const data = buffer.subarray(start, start + size);
    files[name] = (method === 8 ? inflateRawSync(data) : data).toString();
  }
  return files;
}
const streamText = stream => Buffer.from(decodePDFRawStream(stream).decode()).toString();
function extractPdf(pdf) {
  return pdf.getPages().map(page => {
    const fonts = page.node.Resources().lookup(PDFName.of("Font"));
    const dictionary = pdf.context.lookup(fonts.values()[0]);
    const cmap = streamText(dictionary.lookup(PDFName.of("ToUnicode")));
    const characters = new Map();
    for (const section of cmap.matchAll(/beginbfchar([\s\S]*?)endbfchar/g)) {
      for (const [, from, to] of section[1].matchAll(/<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>/g)) {
        characters.set(parseInt(from, 16), (to.match(/.{4}/g) ?? []).map(hex => String.fromCharCode(parseInt(hex, 16))).join(""));
      }
    }
    const contents = page.node.Contents();
    return contents.asArray().map(ref => streamText(pdf.context.lookup(ref))).join("\n").replace(/<([0-9A-Fa-f]+)>\s*Tj/g, (_, hex) => `TEXT:${(hex.match(/.{4}/g) ?? []).map(code => characters.get(parseInt(code, 16)) ?? "").join("")}:ENDTEXT`)
      .match(/TEXT:.*?:ENDTEXT/g)?.map(text => text.slice(5, -8)).join("") ?? "";
  }).join("\n");
}

test("DOCX contains real headings, rich runs, numbering, table, hyperlinks and complete Chinese text", async () => {
  const { service, uploads } = harness();
  await service.create(42, "blog.md", "docx", sample);
  const file = uploads[0];
  assert.equal(file.name, "blog.docx"); assert.equal(file.userId, 42); assert.equal(file.size, file.bytes.length);
  const zip = unzip(file.bytes), xml = zip["word/document.xml"];
  for (const value of ['w:pStyle w:val="Heading1"', "<w:b/>", "<w:i/>", "<w:tbl>", "<w:numPr>", "<w:hyperlink", "中文博客导出验证", "END_OF_DOCUMENT", "中文代码"]) assert.ok(xml.includes(value), value);
  assert.ok(zip["word/numbering.xml"].includes('w:start w:val="3"'));
  assert.ok(zip["word/_rels/document.xml.rels"].includes("https://example.com/blog"));
  assert.ok(!xml.includes("**加粗内容**")); assert.ok(!xml.includes("# 中文博客"));
});

test("PDF embeds Chinese font, converts markdown, preserves links and paginates all long content", async () => {
  const { service, uploads } = harness();
  const long = sample + "\n" + Array.from({ length: 130 }, (_, i) => `第${i}段：` + "长段落自动换行，中文完整保留。".repeat(12)).join("\n\n") + "\n\n最终内容FINAL_SENTINEL";
  await service.create(42, "blog.md", "pdf", long);
  const file = uploads[0]; assert.equal(file.name, "blog.pdf"); assert.equal(file.type, "application/pdf");
  const pdf = await PDFDocument.load(file.bytes), text = extractPdf(pdf);
  assert.ok(pdf.getPageCount() > 3);
  assert.ok(text.includes("中文博客导出验证")); assert.ok(text.includes("中文代码"));
  assert.ok(text.includes("FINAL_SENTINEL")); assert.ok(text.includes("第129段"));
  assert.ok(!text.includes("**加粗内容**"));
  assert.ok(pdf.getPages()[0].node.Annots().size() > 0);
  const objects = pdf.context.enumerateIndirectObjects().map(([, object]) => object.toString()).join("\n");
  assert.match(objects, /\/FontFile2/); assert.match(objects, /\/ToUnicode/);
  // Text extraction alone misses corrupt glyph outlines (odd source glyf offsets
  // are truncated by fontkit's short-loca subsetting). Validate actual outlines.
  const fontDict = pdf.context.lookup(pdf.getPages()[0].node.Resources().lookup(PDFName.of("Font")).values()[0]);
  const descendant = fontDict.lookup(PDFName.of("DescendantFonts")).lookup(0);
  const embedded = fontkit.create(decodePDFRawStream(descendant.lookup(PDFName.of("FontDescriptor")).lookup(PDFName.of("FontFile2"))).decode());
  const original = fontkit.create(await font());
  const cmap = streamText(fontDict.lookup(PDFName.of("ToUnicode")));
  for (const char of "中文博客导出验证Word") {
    const match = cmap.match(new RegExp(`<([0-9a-f]+)>\\s*<${char.charCodeAt(0).toString(16).padStart(4, "0")}>`, "i"));
    assert.ok(match, char);
    assert.equal(embedded.getGlyph(parseInt(match[1], 16)).path.toSVG(), original.glyphForCodePoint(char.codePointAt(0)).path.toSVG(), `glyph outline: ${char}`);
  }
});

test("oversized table rows continue across pages without discarding their tail", async () => {
  const { service, uploads } = harness();
  await service.create(1, "table.pdf", "pdf", "| 内容 | 状态 |\n| --- | --- |\n| " + "跨页表格正文".repeat(500) + "TABLE_TAIL | 正常 |\n");
  const pdf = await PDFDocument.load(uploads[0].bytes);
  assert.ok(pdf.getPageCount() > 1); assert.ok(extractPdf(pdf).includes("TABLE_TAIL"));
});

test("literal code is preserved, unsafe links are inactive and image references remain visible", () => {
  const blocks = parseDocumentMarkdown('**粗体** &amp; &#20013;\n\n`a < b && c`\n\n[危险](javascript:alert)\n\n![插图](https://example.com/image.png)');
  assert.equal(blocks[0].spans[0].bold, true); assert.equal(blocks[0].spans[1].text, " & 中");
  assert.equal(blocks[1].spans[0].text, "a < b && c");
  assert.equal(blocks[2].spans[0].link, undefined);
  assert.match(blocks[3].spans[0].text, /插图.*https:\/\/example.com\/image.png/);
});

test("invalid or oversized documents fail before upload instead of silently truncating", async () => {
  const { service, uploads } = harness();
  await assert.rejects(service.create(1, "large.docx", "docx", "文".repeat(200_001)), /200000/);
  await assert.rejects(service.create(1, "empty.pdf", "pdf", "  "), /不能为空/);
  assert.equal(uploads.length, 0);
});
