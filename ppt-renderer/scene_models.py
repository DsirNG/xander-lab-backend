"""Transport-only contracts for the shared 1280 x 720 presentation scene."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Palette(BaseModel):
    model_config = ConfigDict(extra="forbid")
    background: str = Field(pattern=r"^#[a-fA-F0-9]{6}$")
    foreground: str = Field(pattern=r"^#[a-fA-F0-9]{6}$")
    muted: str = Field(pattern=r"^#[a-fA-F0-9]{6}$")
    accent: str = Field(pattern=r"^#[a-fA-F0-9]{6}$")
    surface: str = Field(pattern=r"^#[a-fA-F0-9]{6}$")


class Element(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    type: Literal["text", "shape", "image", "svg"]
    x: float = Field(ge=0, le=1280)
    y: float = Field(ge=0, le=720)
    width: float = Field(ge=1, le=1280)
    height: float = Field(ge=1, le=720)
    text: str = Field(default="", max_length=4000)
    fontSize: float = Field(default=28, ge=8, le=160)
    color: str | None = Field(default=None, pattern=r"^#[a-fA-F0-9]{6}$")
    bold: bool = False
    align: Literal["left", "center", "right"] = "left"
    fill: str | None = Field(default=None, pattern=r"^#[a-fA-F0-9]{6}$")
    stroke: str | None = Field(default=None, pattern=r"^#[a-fA-F0-9]{6}$")
    strokeWidth: float = Field(default=1, ge=0, le=30)
    shape: Literal["rect", "ellipse", "line"] = "rect"
    radius: float = Field(default=0, ge=0, le=360)
    opacity: float = Field(default=1, ge=0, le=1)
    attachmentId: int | None = Field(default=None, ge=1, le=9007199254740991)
    assetId: int | None = Field(default=None, ge=1, le=9007199254740991)
    svg: str = Field(default="", max_length=32000)
    alt: str = Field(default="", max_length=300)
    fit: Literal["cover", "contain"] = "cover"

    @model_validator(mode="after")
    def technical_contract(self):
        if self.x + self.width > 1280.01 or self.y + self.height > 720.01:
            raise ValueError("Element outside canvas")
        if self.type == "text" and not self.text.strip():
            raise ValueError("Text element is empty")
        if self.type == "image" and (self.assetId is None) == (self.attachmentId is None):
            raise ValueError("Image requires exactly one owned reference")
        if self.type == "svg" and not self.svg.strip():
            raise ValueError("SVG element is empty")
        return self


class Asset(BaseModel):
    model_config = ConfigDict(extra="forbid")
    key: str = Field(pattern=r"^(media|attachment):[1-9][0-9]{0,15}$")
    contentType: Literal["image/png", "image/jpeg", "image/webp", "image/gif"]
    data: str = Field(max_length=12 * 1024 * 1024)
