# Agent Word/PDF exports

`emit_artifact` accepts Markdown source and generates binary attachments on the
Agent Worker before returning a successful artifact. Explicit `format` wins over
the filename; `.docx`/`.pdf` filenames also infer the format. File paths, entry,
MIME type and attachment metadata stay consistent. Multiple files are supported.

Markdown is parsed once with marked, then rendered to DOCX using docx and to PDF
using pdf-lib with the bundled OFL Chinese font. Supported structures: headings,
paragraphs, emphasis, strikethrough, links, lists, quotations, code and GFM tables.
PDF uses A4 pages, wrapped text, repeated table headers, split tall rows and page
numbers. Word uses native paragraphs, headings, numbering and tables.

Current limits: 200,000 source characters per document, 250 PDF pages, 12 PDF table
columns and bounded nesting/header height. Exceeding a limit fails explicitly;
document source is never silently sliced. Images are preserved as captions and
source links, not fetched/embedded. Raw HTML remains literal, not executed. PDF
characters outside the font's coverage are represented by `[U+...]` instead of
invisible glyphs; mathematical typesetting and emoji fonts are not included.

The frontend downloads generated attachment URLs. Historical Word/PDF cards with
only source text show a request to regenerate, rather than generating corrupt
documents in the browser. Existing persisted files are not retroactively rewritten.

No new external document conversion service or secrets are required. Deploy the
Agent and Gateway Workers and the frontend together. The bundled font makes the
Agent compressed bundle about 7 MiB; verify the target account's Worker size limit
before release. `wrangler deploy --dry-run` verifies packaging without publishing.

Validation: `pnpm check` in Workers; frontend build, ESLint, i18n checks and artifact
tests. `tests/document-rendering.test.mjs` checks DOCX XML structure, complete PDF
text, pagination and embedded glyph outlines. Also render a PDF from an actual
local Wrangler runtime to inspect typography and cross-page table continuity.
