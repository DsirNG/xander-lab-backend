# Document export font

`document-sans.ttf` is a renamed, static 400-weight derivative of Google's
Noto Sans SC, distributed under the SIL Open Font License in `OFL.txt`.
The copyright and license records are retained. No operating-system fonts are used.

Upstream: https://github.com/google/fonts/tree/main/ofl/notosanssc
Source file: `NotoSansSC[wght].ttf`
Source SHA-256: `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`

To reproduce: install `fonttools==4.68.0` in an isolated environment, download
the upstream file, verify the above hash, then run:

```text
python build-document-font.py /path/to/NotoSansSC.ttf
```

The script pins the weight, removes vertical/advanced shaping tables, renames
the derivative, and aligns glyph offsets for fontkit subsetting. Use uncompressed
TTF: WOFF causes repeated decompression during layout and is much slower. Wrangler
compresses the deployment bundle; each PDF embeds only the used glyphs.

The renderer intentionally imports fontkit's UMD build and passes a browser Buffer.
Changing either to the ESM build or raw Uint8Array corrupts subset outlines in
fontkit 1.1.1. Tests compare actual embedded glyph paths, not just extracted text.
