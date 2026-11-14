"""Offline maintainer utility. Requires fonttools==4.68.0; not part of Workers runtime."""
import hashlib
import sys
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

source = Path(sys.argv[1])
expected = "a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da"
if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
    raise SystemExit("Unexpected upstream font revision; review source and license before updating")
font = TTFont(source, recalcTimestamp=False)
font = instantiateVariableFont(font, {"wght": 400}, inplace=True)
# Horizontal Chinese/Latin documents do not need vertical or shaping tables.
for tag in ("GSUB", "GPOS", "GDEF", "BASE", "vhea", "vmtx", "VORG"):
    if tag in font:
        del font[tag]
names = {1: "Dinqor Document Sans", 2: "Regular", 3: "DinqorDocumentSans-Regular",
         4: "Dinqor Document Sans Regular", 6: "DinqorDocumentSans-Regular",
         16: "Dinqor Document Sans", 17: "Regular"}
for record in font["name"].names:
    if record.nameID in names:
        record.string = names[record.nameID].encode(record.getEncoding())
# fontkit's short-loca subsetter divides offsets by two; odd glyph sizes lose bytes.
font["glyf"].padding = 2
font.flavor = None
font.recalcTimestamp = False
target = Path(__file__).with_name("document-sans.ttf")
font.save(target)
assert all(offset % 2 == 0 for offset in TTFont(target)["loca"].locations)
print(hashlib.sha256(target.read_bytes()).hexdigest())
