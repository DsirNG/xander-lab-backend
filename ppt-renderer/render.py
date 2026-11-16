"""Compile bounded slide data through PPT Master's quality gate and native exporter."""
import base64
import json
import os
import tempfile
import zipfile
from pathlib import Path

import resvg_py
from pydantic import BaseModel, ConfigDict, Field, HttpUrl
from page_scene import decode_assets, prepare_scene, public_scene, scene_svg
from scene_models import Asset, Element, Palette
from native_pptx import export_deck

REVISION = "b243a950e9f85998705551fb1b77c6459573ff39"
FONT = "Noto Sans CJK SC"


class Source(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=1, max_length=120)
    url: HttpUrl


class ContentBlock(BaseModel):
    model_config = ConfigDict(extra="forbid")
    heading: str = Field(min_length=1, max_length=32)
    body: str = Field(min_length=1, max_length=100)


class ChartPoint(BaseModel):
    model_config = ConfigDict(extra="forbid")
    label: str = Field(min_length=1, max_length=24)
    value: float = Field(ge=0, le=1e12, allow_inf_nan=False)


class Chart(BaseModel):
    model_config = ConfigDict(extra="forbid")
    unit: str = Field(default="", max_length=12)
    caption: str = Field(default="", max_length=100)
    points: list[ChartPoint] = Field(default_factory=list)


class Slide(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=1, max_length=70)
    layout: str = Field(default="bullets", pattern="^(canvas|cover|bullets|columns|cards|process|comparison|chart|closing)$")
    background: str | None = Field(default=None, pattern=r"^#[a-fA-F0-9]{6}$")
    elements: list[Element] = Field(default_factory=list, max_length=200)
    kicker: str = Field(default="", max_length=28)
    subtitle: str = Field(default="", max_length=80)
    takeaway: str = Field(default="", max_length=100)
    blocks: list[ContentBlock] = Field(default_factory=list)
    chart: Chart | None = None
    bullets: list[str] = Field(default_factory=list)
    notes: str = Field(default="", max_length=2000)
    sources: list[Source] = Field(default_factory=list)


class Deck(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=1, max_length=100)
    language: str = Field(default="zh-CN", pattern="^(zh-CN|en-US)$")
    theme: str = Field(default="navy", pattern="^(navy|paper)$")
    palette: Palette | None = None
    assets: list[Asset] = Field(default_factory=list, max_length=200)
    slides: list[Slide] = Field(min_length=1, max_length=20)


def slide_svg(deck: Deck, slide: Slide, index: int) -> str:
    return scene_svg(prepare_scene(deck, slide, index, decode_assets(deck.assets)))

def compile_deck(deck: Deck) -> dict:
    with tempfile.TemporaryDirectory(prefix="dinqor-ppt-") as directory:
        project = Path(directory)
        (project / "metadata.json").write_text(json.dumps({
            "title": deck.title, "creator": "DinQorAI", "company": "DinQorAI", "language": deck.language,
        }, ensure_ascii=False), encoding="utf-8")
        (project / "svg_output").mkdir()
        (project / "notes").mkdir()
        previews, svgs, scenes = [], [], []
        assets = decode_assets(deck.assets)
        for index, slide in enumerate(deck.slides):
            scene = prepare_scene(deck, slide, index, assets)
            scenes.append(scene)
            svg = scene_svg(scene)
            svgs.append(svg)
            name = f"slide_{index + 1:02d}"
            (project / "svg_output" / f"{name}.svg").write_text(svg, encoding="utf-8")
            notes = slide.notes + "\n\n" + "\n".join(f"{source.title}: {source.url}" for source in slide.sources)
            (project / "notes" / f"{name}.md").write_text(notes, encoding="utf-8")
            font_file = os.environ.get("PPT_PREVIEW_FONT")
            png = resvg_py.svg_to_bytes(svg_string=svg, width=960, height=540,
                                       font_files=[font_file] if font_file else None)
            previews.append(base64.b64encode(png).decode())
        export_deck(deck, project / "deck.pptx", scenes)
        pptx = (project / "deck.pptx").read_bytes()
        with zipfile.ZipFile(project / "deck.pptx") as archive:
            if len([name for name in archive.namelist() if __import__('re').fullmatch(r"ppt/slides/slide\d+\.xml", name)]) != len(deck.slides):
                raise RuntimeError("PPTX page count mismatch")
        return {"revision": REVISION, "pptx": base64.b64encode(pptx).decode(), "previews": previews,
                "source": {"deck": deck.model_dump(mode="json", exclude={"assets"}), "scenes": [public_scene(scene) for scene in scenes]},
                "quality": {"passed": True, "reportGenerated": False, "engine": "dinqor-scene-v2", "checks": ["text-fit", "asset-decode", "slide-count", "shared-scene"], "visualReview": "agent-required"}}
