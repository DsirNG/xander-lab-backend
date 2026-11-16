"""Export shared scenes as editable shapes/text and replaceable raster/vector pictures."""
import io
from pathlib import Path
from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.oxml.xmlchemy import OxmlElement
from pptx.opc.package import Part
from pptx.opc.constants import RELATIONSHIP_TYPE as RT
from pptx.util import Inches, Pt
from page_scene import FONT, decode_assets, prepare_scene


def units(px):
    return Inches(px / 96)


def rgb(value):
    return RGBColor.from_string(value.lstrip('#'))


def alpha(color_node, opacity):
    if opacity != 1:
        child = OxmlElement('a:alpha')
        child.set('val', str(round(opacity * 100000)))
        color_node.append(child)


def export_deck(deck, output: Path, scenes=None):
    if scenes is None:
        assets = decode_assets(deck.assets)
        scenes = [prepare_scene(deck, item, i, assets) for i, item in enumerate(deck.slides)]
    prs = Presentation()
    prs.core_properties.title = deck.title
    prs.core_properties.author = 'DinQorAI'
    prs.slide_width, prs.slide_height = units(1280), units(720)
    for item, scene in zip(deck.slides, scenes):
        slide = prs.slides.add_slide(prs.slide_layouts[6])
        slide.background.fill.solid()
        slide.background.fill.fore_color.rgb = rgb(scene['background'])
        for el in scene['elements']:
            x, y, w, h = (units(el[k]) for k in ('x', 'y', 'width', 'height'))
            opacity = el.get('opacity', 1)
            if el['type'] == 'text':
                # The same measured lines drive preview and export, never two independent layouts.
                for n, line in enumerate(el['_lines']):
                    box = slide.shapes.add_textbox(x, y + units(n * el['_lineHeight']), w, units(el['_lineHeight']))
                    frame = box.text_frame
                    frame.clear()
                    frame.margin_left = frame.margin_right = frame.margin_top = frame.margin_bottom = 0
                    frame.word_wrap = False
                    frame.vertical_anchor = MSO_ANCHOR.TOP
                    paragraph = frame.paragraphs[0]
                    paragraph.alignment = {'left': PP_ALIGN.LEFT, 'center': PP_ALIGN.CENTER, 'right': PP_ALIGN.RIGHT}[el['align']]
                    paragraph.space_before = paragraph.space_after = Pt(0)
                    run = paragraph.add_run()
                    run.text = line
                    run.font.name = FONT
                    run.font.size = Pt(el['fontSize'] * .75)
                    run.font.bold = el['bold']
                    run.font.color.rgb = rgb(el['color'])
                    ea = OxmlElement('a:ea'); ea.set('typeface', FONT); run._r.get_or_add_rPr().append(ea)
                    alpha(run._r.get_or_add_rPr().solidFill.srgbClr, opacity)
            elif el['type'] == 'shape':
                kind = el.get('shape', 'rect')
                if kind == 'line':
                    shape = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, x, y, x + w, y + h)
                else:
                    shape = slide.shapes.add_shape(MSO_SHAPE.OVAL if kind == 'ellipse' else MSO_SHAPE.ROUNDED_RECTANGLE if el.get('radius') else MSO_SHAPE.RECTANGLE, x, y, w, h)
                    if kind == 'rect' and el.get('radius'):
                        shape.adjustments[0] = min(.5, el['radius'] / min(el['width'], el['height']))
                    if el.get('fill'):
                        shape.fill.solid(); shape.fill.fore_color.rgb = rgb(el['fill'])
                        alpha(shape._element.spPr.solidFill.srgbClr, opacity)
                    else:
                        shape.fill.background()
                if el.get('stroke'):
                    shape.line.color.rgb = rgb(el['stroke'])
                    shape.line.width = Pt(el.get('strokeWidth', 1) * .75)
                    alpha(shape._element.spPr.ln.solidFill.srgbClr, opacity)
                else:
                    shape.line.fill.background()
            else:
                picture = slide.shapes.add_picture(io.BytesIO(el['_png']), x, y, w, h)
                picture._element.nvPicPr.cNvPr.set('descr', el.get('alt', ''))
                if el.get('_svg'):
                    package = prs.part.package
                    vector = Part(package.next_partname('/ppt/media/vector%d.svg'), 'image/svg+xml', package, el['_svg'])
                    relation = slide.part.relate_to(vector, RT.IMAGE)
                    ext_list = OxmlElement('a:extLst')
                    ext = OxmlElement('a:ext'); ext.set('uri', '{96DAC541-7B7A-43D3-8B79-37D633B846F1}')
                    svg_blip = etree.Element('{http://schemas.microsoft.com/office/drawing/2016/SVG/main}svgBlip', nsmap={'asvg': 'http://schemas.microsoft.com/office/drawing/2016/SVG/main'})
                    svg_blip.set('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}embed', relation)
                    ext.append(svg_blip); ext_list.append(ext)
                    picture._element.blipFill.blip.append(ext_list)
        notes = item.notes
        if item.sources:
            notes += '\n\n' + '\n'.join(f'{s.title}: {s.url}' for s in item.sources)
        if notes:
            slide.notes_slide.notes_text_frame.text = notes
    prs.save(output)
