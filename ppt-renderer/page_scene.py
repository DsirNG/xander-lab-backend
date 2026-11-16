"""Build one scene, including resolved image crops and wrapped text, for both exporters."""
import base64
import io
import math
import os
from functools import lru_cache
from pathlib import Path
from xml.sax.saxutils import escape
from xml.etree import ElementTree as ET

import resvg_py
from PIL import Image, ImageDraw, ImageFont, ImageOps

from svg_safety import safe_svg

FONT = "Noto Sans CJK SC"
Image.MAX_IMAGE_PIXELS = 16000000


@lru_cache(maxsize=64)
def font(size, bold=False, family=FONT):
    serif = family == "Noto Serif CJK SC"
    mono = family == "Noto Sans Mono CJK SC"
    stem = "NotoSerifCJK" if serif else "NotoSansCJK"
    candidates = [os.environ.get("PPT_PREVIEW_FONT", ""),
                  f"/usr/share/fonts/opentype/noto/{stem}-{'Bold' if bold else 'Regular'}.ttc",
                  str(Path(os.environ.get("PPT_FONT_DIRECTORY", "/usr/share/fonts/opentype/noto")) / f"{stem}-{'Bold' if bold else 'Regular'}.ttc"),
                  "C:/Windows/Fonts/simsun.ttc" if serif else "C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"]
    for path in candidates:
        if path and Path(path).exists():
            return ImageFont.truetype(path, max(1, round(size)), index=(7 if mono else 2) if "Noto" in path else 0)
    raise RuntimeError("A CJK font is required for preview and text measurement")


def wrap_text(value, size, width, bold, family=FONT):
    measured = font(size, bold, family)
    result = []
    for paragraph in value.split("\n"):
        if not paragraph:
            result.append("")
            continue
        line = ""
        # Preserve every character. Prefer a word boundary when a Latin word crosses a line.
        for char in paragraph:
            candidate = line + char
            if line and measured.getlength(candidate) > width:
                split = line.rfind(" ")
                if split > 0 and ord(char) < 128 and char.isalnum() and line[-1].isalnum():
                    result.append(line[:split])
                    line = line[split + 1:] + char
                else:
                    result.append(line)
                    line = char
            else:
                line = candidate
        result.append(line)
    return result


def legacy_elements(deck, item, index):
    """Compatibility for saved decks; new Agent designs use explicit canvas elements."""
    p = palette(deck)
    out = []
    def text(value, x, y, w, h, size=26, color=None, bold=False, align="left"):
        if value:
            out.append(dict(type="text", text=value, x=x, y=y, width=w, height=h,
                            fontSize=size, color=color or p["foreground"], bold=bold, align=align))
    def shape(x, y, w, h, fill, radius=0):
        out.append(dict(type="shape", shape="rect", x=x, y=y, width=w, height=h, fill=fill, radius=radius))
    text(item.kicker or deck.title, 64, 32, 1050, 30, 16, p["accent"])
    text(item.title, 64, 100, 1152, 160 if item.layout == "cover" else 110,
         58 if item.layout == "cover" else 40, bold=True)
    text(item.subtitle, 64, 265 if item.layout == "cover" else 210, 1152, 90, 25, p["muted"])
    blocks = item.blocks or []
    bullets = list(item.bullets)
    if item.layout == "cover":
        for n, value in enumerate(bullets):
            height = 270 / max(len(bullets), 1)
            text(value, 64, 365 + n * height, 1152, height, 26, p["muted"])
    elif item.layout == "chart" and item.chart and item.chart.points:
        points = item.chart.points
        maximum = max((v.value for v in points), default=1) or 1
        step = 280 / max(len(points), 1)
        for n, point in enumerate(points):
            y = 300 + n * step
            text(point.label, 64, y, 240, step, min(24, step * .65))
            shape(320, y + 5, max(1, 700 * point.value / maximum), max(1, step - 12), p["accent"])
            text(f"{point.value:g}{item.chart.unit}", 1040, y, 175, step, min(24, step * .65))
        text(item.chart.caption, 64, 583, 1152, 34, 16, p["muted"])
    elif blocks:
        count = len(blocks)
        columns = count if item.layout == "process" else min(3 if count > 4 else 2, count)
        rows = math.ceil(count / columns)
        cell_w = (1152 - (columns - 1) * 24) / columns
        cell_h = (300 - (rows - 1) * 20) / rows
        for n, block in enumerate(blocks):
            x, y = 64 + (n % columns) * (cell_w + 24), 290 + (n // columns) * (cell_h + 20)
            shape(x, y, cell_w, cell_h, p["surface"], 10)
            text(block.heading, x + 20, y + 16, cell_w - 40, cell_h * .38, 24, p["accent"], True)
            text(block.body, x + 20, y + cell_h * .42, cell_w - 40, cell_h * .52, 20)
        if bullets:
            # Do not lose narrative bullets supplied alongside structured blocks.
            text("  ·  ".join(bullets), 64, 600, 1152, 52, 18, p["muted"])
    else:
        for n, value in enumerate(bullets):
            step = 310 / max(len(bullets), 1)
            text(value, 64, 290 + n * step, 1152, step - 4, 26)
    if item.takeaway:
        text(item.takeaway, 64, 650, 1080, 36, 18, p["accent"])
    text(f"{index + 1:02d} / {len(deck.slides):02d}", 1120, 685, 100, 25, 12, p["muted"], align="right")
    return out


def palette(deck):
    if deck.palette:
        return deck.palette.model_dump()
    return dict(background="#0B192B", foreground="#F5F8FC", muted="#A9BDD2", accent="#60DDCC", surface="#142A42") if deck.theme == "navy" else dict(background="#F7F5EF", foreground="#192C35", muted="#596B73", accent="#167B70", surface="#FFFFFF")


def decode_assets(assets):
    result, total = {}, 0
    for asset in assets:
        try:
            raw = base64.b64decode(asset.data, validate=True)
            total += len(raw)
            if len(raw) > 8 * 1024 * 1024 or total > 20 * 1024 * 1024 or asset.key in result:
                raise ValueError()
            with Image.open(io.BytesIO(raw)) as image:
                if image.format not in ("PNG", "JPEG", "WEBP", "GIF") or image.width * image.height > 16000000:
                    raise ValueError()
                result[asset.key] = ImageOps.exif_transpose(image).convert("RGBA")
        except Exception:
            raise ValueError(f"PPT_ASSET_INVALID: {asset.key} is not a readable bounded PNG/JPEG/WebP/GIF image") from None
    return result


def prepare_scene(deck, item, index, assets):
    p = palette(deck)
    source = [v.model_dump(exclude_none=True) for v in item.elements] if item.layout == "canvas" else legacy_elements(deck, item, index)
    if item.layout != "canvas" and item.elements:
        source.extend(v.model_dump(exclude_none=True) for v in item.elements)
    scene = {"background": item.background or p["background"], "elements": []}
    if item.layout == "canvas" and not source:
        raise ValueError(f"PPT_TEXT_FIT: page {index + 1} canvas has no elements; author visible content in elements")
    for n, original in enumerate(source):
        el = dict(original)
        el.setdefault("opacity", 1)
        if el["type"] == "text":
            el.setdefault("fontSize", 28)
            el.setdefault("bold", False)
            el.setdefault("align", "left")
            el.setdefault("color", p["foreground"])
            el.setdefault("fontFamily", FONT)
            el["_lines"] = wrap_text(el["text"], el["fontSize"], el["width"], el["bold"], el["fontFamily"])
            el["_lineHeight"] = el["fontSize"] * 1.35
            if len(el["_lines"]) * el["_lineHeight"] > el["height"] + .1:
                raise ValueError(f"PPT_TEXT_FIT: page {index + 1} element {n + 1} needs {len(el['_lines']) * el['_lineHeight']:.0f}px height, has {el['height']:g}px; enlarge the box or rewrite the text")
        elif el["type"] in ("image", "svg"):
            w, h = max(1, round(el["width"] * 1.5)), max(1, round(el["height"] * 1.5))
            if el["type"] == "svg":
                vector_root = ET.fromstring(safe_svg(el["svg"]))
                vector_root.set("width", str(w)); vector_root.set("height", str(h))
                vector_root.set("preserveAspectRatio", "xMidYMid meet" if el.get("fit") == "contain" else "xMidYMid slice")
                nested = ET.tostring(vector_root, encoding="unicode")
                radius = el.get("radius", 0) * 1.5
                clip = f'<defs><clipPath id="dinqorViewport"><rect width="{w}" height="{h}" rx="{radius}"/></clipPath></defs>'
                vector = f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">{clip}<g opacity="{el["opacity"]}" clip-path="url(#dinqorViewport)">{nested}</g></svg>'
                # Both preview and PPTX use this exact raster fallback; original vector is also embedded in PPTX.
                el["_svg"] = vector.encode()
                png = resvg_py.svg_to_bytes(svg_string=vector, width=w, height=h)
                image = Image.open(io.BytesIO(png)).convert("RGBA")
            else:
                key = f"media:{el['assetId']}" if el.get("assetId") else f"attachment:{el['attachmentId']}"
                if key not in assets:
                    raise ValueError(f"PPT_ASSET_INVALID: page {index + 1} element {n + 1} missing {key}")
                image = assets[key].copy()
            if el.get("fit", "cover") == "contain":
                image.thumbnail((w, h), Image.Resampling.LANCZOS)
                canvas = Image.new("RGBA", (w, h))
                canvas.alpha_composite(image, ((w - image.width) // 2, (h - image.height) // 2))
                image = canvas
            else:
                image = ImageOps.fit(image, (w, h), Image.Resampling.LANCZOS)
            if el["type"] == "image" and el.get("radius", 0):
                from PIL import ImageChops
                mask = Image.new("L", image.size)
                ImageDraw.Draw(mask).rounded_rectangle((0, 0, w, h), radius=el["radius"] * 1.5, fill=255)
                image.putalpha(ImageChops.multiply(image.getchannel("A"), mask))
            if el["type"] == "image" and el["opacity"] != 1:
                image.putalpha(image.getchannel("A").point(lambda v: round(v * el["opacity"])))
            output = io.BytesIO()
            image.save(output, format="PNG", optimize=True)
            el["_png"] = output.getvalue()
        scene["elements"].append(el)
    return scene


def scene_svg(scene):
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720"><rect width="1280" height="720" fill="{scene["background"]}"/>']
    for el in scene["elements"]:
        x, y, w, h = el["x"], el["y"], el["width"], el["height"]
        opacity = el.get("opacity", 1)
        if el["type"] == "text":
            size = el["fontSize"]
            align = el["align"]
            anchor = {"left": "start", "center": "middle", "right": "end"}[align]
            tx = x + (w / 2 if align == "center" else w if align == "right" else 0)
            family = el.get("fontFamily", FONT)
            ascent = font(size, el["bold"], family).getmetrics()[0]
            for n, line in enumerate(el["_lines"]):
                out.append(f'<text x="{tx:g}" y="{y + ascent + n * el["_lineHeight"]:g}" font-family="{family}" font-size="{size:g}" font-weight="{700 if el["bold"] else 400}" text-anchor="{anchor}" fill="{el["color"]}" opacity="{opacity}">{escape(line)}</text>')
        elif el["type"] == "shape":
            style = f'fill="{el.get("fill", "none")}" stroke="{el.get("stroke", "none")}" stroke-width="{el.get("strokeWidth", 1)}" opacity="{opacity}"'
            kind = el.get("shape", "rect")
            if kind == "ellipse":
                out.append(f'<ellipse cx="{x + w / 2:g}" cy="{y + h / 2:g}" rx="{w / 2:g}" ry="{h / 2:g}" {style}/>')
            elif kind == "line":
                out.append(f'<line x1="{x:g}" y1="{y:g}" x2="{x + w:g}" y2="{y + h:g}" {style}/>')
            else:
                out.append(f'<rect x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}" rx="{el.get("radius", 0)}" {style}/>')
        else:
            data = base64.b64encode(el["_png"]).decode()
            out.append(f'<image x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}" href="data:image/png;base64,{data}"/>')
    out.append('</svg>')
    return ''.join(out)


def public_scene(scene):
    return {"background": scene["background"], "elements": [{k: v for k, v in el.items() if not k.startswith("_")} for el in scene["elements"]]}
