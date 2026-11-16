import base64
import io
import os
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from render import Deck, compile_deck, slide_svg


class RenderTests(unittest.TestCase):
    def test_semantic_layouts_preserve_heading_body_and_data(self):
        for layout, blocks in [('cards', 4), ('comparison', 2), ('process', 3)]:
            deck = Deck.model_validate({'title': 'Product story', 'slides': [{
                'title': 'Evidence before decisions', 'layout': layout,
                'subtitle': 'A practical workflow', 'takeaway': 'Review before sharing',
                'blocks': [{'heading': f'Step {i}', 'body': 'A concrete deliverable'} for i in range(blocks)]}]})
            svg = slide_svg(deck, deck.slides[0], 0)
            ET.fromstring(svg)
            self.assertEqual(svg.count('A concrete deliverable'), blocks)
            self.assertIn('Review before sharing', svg)
        deck = Deck.model_validate({'title':'Data', 'slides':[{'title':'Sample data', 'layout':'chart',
            'chart':{'unit':'h','caption':'Illustrative input only','points':[{'label':'A','value':0},{'label':'B','value':12.5}]}}]})
        svg = slide_svg(deck, deck.slides[0], 0)
        self.assertIn('12.5h',svg)
        self.assertIn('Illustrative input only',svg)

    def test_rich_layout_accepts_agent_selected_content(self):
        for page in [
            {'title':'No loss','layout':'cards','bullets':['Must remain visible'],'blocks':[{'heading':'A','body':'B'}]*2},
            {'title':'Flexible','layout':'cards','blocks':[{'heading':'A','body':'短内容'}]*6},
            {'title':'Missing data','layout':'chart'},
        ]:
            deck = Deck.model_validate({'title':'Bounds','slides':[page]})
            svg = slide_svg(deck, deck.slides[0], 0)
            ET.fromstring(svg)

    def test_text_is_escaped_and_no_active_content_is_created(self):
        deck = Deck.model_validate({"title": "Safe", "slides": [{"title": '<script>alert("x")</script>', "bullets": ["A & B"]}]})
        svg = slide_svg(deck, deck.slides[0], 0)
        root = ET.fromstring(svg)
        self.assertNotIn("<script>", svg)
        self.assertIn('A &amp; B', svg)
        self.assertEqual(len(list(root.iter("{http://www.w3.org/2000/svg}script"))), 0)

    def test_layout_keeps_agent_selected_content_without_semantic_count_limits(self):
        deck = Deck.model_validate({"title": "Bounds", "slides": [{"title": "Title", "layout": "cover", "bullets": ["x", "y", "z"]}]})
        svg = slide_svg(deck, deck.slides[0], 0)
        self.assertIn('>z<', svg)

    def test_native_exports_editable_shapes_and_two_previews(self):
        deck = Deck.model_validate({"title": "Native", "language": "en-US", "slides": [
            {"title": "Editable presentations", "layout": "cover", "bullets": ["A reliable production workflow"]},
            {"title": "Evidence and execution", "bullets": ["Plan the outline", "Validate every page", "Export native shapes"],
             "notes": "Speaker notes remain editable", "sources": [{"title": "Project requirements", "url": "https://example.org/report"}]},
        ]})
        result = compile_deck(deck)
        with zipfile.ZipFile(io.BytesIO(base64.b64decode(result["pptx"]))) as archive:
            slide = archive.read("ppt/slides/slide2.xml").decode()
            self.assertIn("Plan the outline", slide)
            self.assertIn("<p:sp>", slide)
            self.assertNotIn("<p:pic>", slide)
            core = archive.read("docProps/core.xml").decode()
            self.assertIn("<dc:creator>DinQorAI</dc:creator>", core)
            self.assertIn("<dc:title>Native</dc:title>", core)
            for name in archive.namelist():
                if name.startswith(("ppt/slides/slide", "docProps/")) and name.endswith(".xml"):
                    self.assertNotIn("PPT Master", archive.read(name).decode())
        self.assertEqual(len(result["previews"]), 2)
        self.assertTrue(result["quality"]["passed"])
        self.assertEqual(result["quality"]["engine"], "dinqor-scene-v2")
        output = os.environ.get("PPT_TEST_OUTPUT")
        if output:
            path = Path(output)
            path.mkdir(parents=True, exist_ok=True)
            (path / "native.pptx").write_bytes(base64.b64decode(result["pptx"]))
            for index, png in enumerate(result["previews"]):
                (path / f"preview-{index + 1}.png").write_bytes(base64.b64decode(png))


if __name__ == "__main__":
    unittest.main()
