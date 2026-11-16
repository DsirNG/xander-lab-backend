"""Self-contained vector input; never resolves links, scripts, CSS or external resources."""
import re
from xml.etree import ElementTree as ET

ALLOWED = {"svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "tspan", "defs", "linearGradient", "radialGradient", "stop", "clipPath"}
ATTRS = {"id", "viewBox", "width", "height", "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry", "d", "points", "fill", "fill-rule", "fill-opacity", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "opacity", "transform", "font-family", "font-size", "font-weight", "text-anchor", "dominant-baseline", "dx", "dy", "offset", "stop-color", "stop-opacity", "gradientUnits", "gradientTransform", "fx", "fy", "clip-path", "clipPathUnits", "preserveAspectRatio"}


def safe_svg(value):
    def fail():
        raise ValueError("PPT_SVG_INVALID: SVG must contain only self-contained shapes, text and gradients; remove external resources or scripts")
    if len(value.encode()) > 128000 or "<!" in value or "<?" in value:
        fail()
    try:
        root = ET.fromstring(value)
    except ET.ParseError:
        fail()
    if root.tag not in ("svg", "{http://www.w3.org/2000/svg}svg"):
        fail()
    nodes = list(root.iter())
    if len(nodes) > 1500:
        fail()
    for node in nodes:
        tag = node.tag.removeprefix("{http://www.w3.org/2000/svg}")
        if tag not in ALLOWED:
            fail()
        for key, val in node.attrib.items():
            if key not in ATTRS or re.search(r"(?:https?:|data:|file:|javascript:|@|[<>])", val, re.I):
                fail()
            if "url(" in val.lower() and not re.fullmatch(r"url\(#[A-Za-z_][\w.-]*\)", val):
                fail()
            if key == "font-size":
                try:
                    if not 1 <= float(val) <= 256:
                        fail()
                except ValueError:
                    fail()
        node.tag = "{http://www.w3.org/2000/svg}" + tag
    # Keep user geometry; viewport dimensions are controlled by the scene.
    if "viewBox" not in root.attrib:
        try:
            w, h = float(root.get("width", "0")), float(root.get("height", "0"))
            if not (0 < w <= 10000 and 0 < h <= 10000):
                fail()
            root.set("viewBox", f"0 0 {w:g} {h:g}")
        except ValueError:
            fail()
    try:
        dims = [float(v) for v in re.split(r"[\s,]+", root.get("viewBox", "").strip())]
        if len(dims) != 4 or not all(abs(v) <= 10000 for v in dims) or dims[2] <= 0 or dims[3] <= 0:
            fail()
    except ValueError:
        fail()
    root.set("width", f"{dims[2]:g}")
    root.set("height", f"{dims[3]:g}")
    ET.register_namespace("", "http://www.w3.org/2000/svg")
    return ET.tostring(root, encoding="unicode")
