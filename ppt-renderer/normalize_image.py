"""Decode bounded source images and rasterize inert SVGs without network access."""
import base64
import io
import re
from xml.etree import ElementTree as ET
import resvg_py
from PIL import Image, ImageOps
from svg_safety import safe_svg


def normalize_image(payload):
    Image.MAX_IMAGE_PIXELS = 16000000
    raw = base64.b64decode(payload['data'], validate=True)
    if not raw or len(raw) > 8 * 1024 * 1024:
        raise ValueError('Image too large')
    if payload['contentType'] == 'image/svg+xml':
        vector = safe_svg(raw.decode('utf-8-sig'))
        root = ET.fromstring(vector)
        dims = [float(v) for v in re.split(r"[\s,]+", root.attrib["viewBox"].strip())]
        # Bound both axes before rasterization, including extreme SVG aspect ratios.
        scale = min(1600 / dims[2], 1600 / dims[3])
        raw = resvg_py.svg_to_bytes(svg_string=vector, width=max(1, round(dims[2] * scale)), height=max(1, round(dims[3] * scale)))
    with Image.open(io.BytesIO(raw)) as source:
        if source.format not in ('PNG', 'JPEG', 'WEBP', 'GIF') or source.width * source.height > 16000000:
            raise ValueError('Invalid image')
        image = ImageOps.exif_transpose(source).convert('RGBA')
        image.thumbnail((2560, 2560), Image.Resampling.LANCZOS)
        output = io.BytesIO(); image.save(output, format='PNG', optimize=True)
        png = output.getvalue()
        if len(png) > 8 * 1024 * 1024:
            raise ValueError('Image too large')
        return dict(data=base64.b64encode(png).decode(), width=image.width, height=image.height)
