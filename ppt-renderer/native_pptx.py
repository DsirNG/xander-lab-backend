from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.util import Inches, Pt


W, H = Inches(13.333), Inches(7.5)
NAVY = RGBColor(8, 24, 52)
WHITE = RGBColor(248, 250, 252)
MUTED = RGBColor(167, 188, 214)
CYAN = RGBColor(54, 198, 238)
CARD = RGBColor(18, 42, 75)


def _text(slide, value, x, y, w, h, size=18, color=WHITE, bold=False, align=PP_ALIGN.LEFT):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    frame = box.text_frame
    frame.clear()
    frame.word_wrap = True
    p = frame.paragraphs[0]
    p.alignment = align
    run = p.add_run()
    run.text = str(value)
    run.font.name = "Noto Sans CJK SC"
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    return box


def _background(slide, theme):
    fill = slide.background.fill
    fill.solid()
    fill.fore_color.rgb = WHITE if theme == "paper" else NAVY


def _card(slide, x, y, w, h):
    shape = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shape.fill.solid(); shape.fill.fore_color.rgb = CARD
    shape.line.color.rgb = RGBColor(37, 75, 112)
    return shape


def export_deck(deck, output: Path):
    prs = Presentation()
    prs.core_properties.title = deck.title
    prs.core_properties.author = "DinQorAI"
    prs.core_properties.company = "DinQorAI"
    prs.slide_width, prs.slide_height = W, H
    blank = prs.slide_layouts[6]
    for index, item in enumerate(deck.slides):
        slide = prs.slides.add_slide(blank)
        _background(slide, deck.theme)
        _text(slide, item.kicker or f"DINQOR / {deck.title}", .65, .38, 6.8, .35, 10, MUTED, True)
        _text(slide, f"{index + 1:02d} / {len(deck.slides):02d}", 11.65, .38, 1, .35, 10, MUTED, False, PP_ALIGN.RIGHT)
        _text(slide, item.title, .65, 1.0, 11.2, .8, 28 if item.layout == "cover" else 24, WHITE, True)
        if item.subtitle:
            _text(slide, item.subtitle, .65, 1.9, 11.2, .5, 14, MUTED)
        blocks = item.blocks or []
        if item.layout == "chart" and item.chart:
            points = item.chart.points
            maximum = max((p.value for p in points), default=1) or 1
            for n, point in enumerate(points):
                x = .9 + n * (11.3 / max(len(points), 1))
                height = 3.6 * point.value / maximum
                shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(6.0 - height), Inches(.55), Inches(height))
                shape.fill.solid(); shape.fill.fore_color.rgb = CYAN; shape.line.fill.background()
                _text(slide, point.label, x - .15, 6.12, .9, .3, 10, MUTED, False, PP_ALIGN.CENTER)
                _text(slide, point.value, x - .15, 5.75 - height, .9, .25, 9, WHITE, True, PP_ALIGN.CENTER)
        elif blocks:
            columns = 2 if len(blocks) > 1 else 1
            cell_w = 5.45 if columns == 2 else 11.2
            for n, block in enumerate(blocks):
                col, row = n % columns, n // columns
                x, y = .65 + col * 5.75, 2.55 + row * 1.45
                _card(slide, x, y, cell_w, 1.12)
                _text(slide, block.heading, x + .22, y + .18, cell_w - .4, .3, 14, CYAN, True)
                _text(slide, block.body, x + .22, y + .53, cell_w - .4, .4, 11, WHITE)
        else:
            for n, bullet in enumerate(item.bullets):
                _text(slide, f"•  {bullet}", .9, 2.55 + n * .62, 11, .42, 15, WHITE)
        if item.takeaway:
            _text(slide, item.takeaway, .65, 6.65, 11.2, .38, 12, MUTED)
    prs.save(output)
