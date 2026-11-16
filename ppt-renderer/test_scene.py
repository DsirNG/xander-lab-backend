import base64
import io
import unittest
import zipfile
from xml.etree import ElementTree as ET
from PIL import Image
from pptx import Presentation
from render import Deck, compile_deck
from page_scene import decode_assets, prepare_scene, scene_svg
from svg_safety import safe_svg

VECTOR = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs><linearGradient id="g"><stop offset="0" stop-color="#ffcc80"/><stop offset="1" stop-color="#885500"/></linearGradient></defs><path fill="url(#g)" d="M20 180 L100 20 L180 180 Z"/></svg>'


def raster(format='PNG'):
    image = Image.new('RGB', (200, 100), '#cc4400')
    image.paste('#00cc88', (100, 0, 200, 100))
    data = io.BytesIO(); image.save(data, format=format)
    return base64.b64encode(data.getvalue()).decode()


def deck_input():
    return {'title': 'A designed deck', 'palette': {'background':'#102034','foreground':'#ffffff','muted':'#aabbcc','accent':'#ffcc88','surface':'#203040'}, 'assets':[
        {'key':'media:7','contentType':'image/png','data':raster()},
    ], 'slides':[{'title':'A unique composition', 'layout':'canvas', 'background':'#faf3e7', 'notes':'Editable notes', 'elements':[
        {'type':'image','assetId':7,'x':720,'y':0,'width':560,'height':720,'fit':'cover','alt':'A product image'},
        {'type':'shape','shape':'rect','x':40,'y':40,'width':300,'height':15,'fill':'#cc4400'},
        {'type':'text','text':'Real content, clear story','x':64,'y':140,'width':580,'height':180,'fontSize':54,'bold':True,'color':'#102034'},
        {'type':'svg','svg':VECTOR,'x':64,'y':400,'width':180,'height':180,'fit':'contain','alt':'A vector illustration'},
    ]}]}


class SceneTests(unittest.TestCase):
    def test_shared_scene_preserves_image_crop_coordinates_editable_text_svg_and_notes(self):
        deck = Deck.model_validate(deck_input())
        scene = prepare_scene(deck, deck.slides[0], 0, decode_assets(deck.assets))
        self.assertNotIn('<circle', scene_svg(scene))
        self.assertEqual(scene['background'], '#faf3e7')
        result = compile_deck(deck)
        self.assertEqual(result['source']['scenes'][0]['elements'][0]['assetId'], 7)
        self.assertNotIn('assets', result['source']['deck'])
        self.assertEqual(result['quality']['visualReview'], 'agent-required')
        raw = base64.b64decode(result['pptx'])
        prs = Presentation(io.BytesIO(raw))
        slide = prs.slides[0]
        picture = slide.shapes[0]
        self.assertAlmostEqual(picture.left / 9525, 720, places=3)
        self.assertAlmostEqual(picture.width / 9525, 560, places=3)
        self.assertEqual(picture.image.blob, scene['elements'][0]['_png'])
        actual = [shape.text for shape in slide.shapes if shape.has_text_frame and shape.text]
        self.assertEqual(actual, scene['elements'][2]['_lines'])
        self.assertIn('Editable notes', slide.notes_slide.notes_text_frame.text)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            vector = [v for v in archive.namelist() if v.endswith('.svg')]
            self.assertEqual(len(vector), 1)
            self.assertIn('linearGradient', archive.read(vector[0]).decode())
            self.assertIn('svgBlip', archive.read('ppt/slides/slide1.xml').decode())
            for name in archive.namelist():
                if name.endswith('.xml'):
                    ET.fromstring(archive.read(name))
        preview = Image.open(io.BytesIO(base64.b64decode(result['previews'][0])))
        self.assertEqual(preview.size, (960, 540))
        self.assertEqual(preview.getpixel((5,5))[:3], (250,243,231))

    def test_svg_viewport_fills_its_actual_box_instead_of_a_fixed_widescreen_viewport(self):
        value=deck_input()
        value['slides'][0]['elements']=[{'type':'svg','svg':VECTOR,'x':100,'y':100,'width':200,'height':500,'fit':'contain'}]
        deck=Deck.model_validate(value)
        el=prepare_scene(deck,deck.slides[0],0,decode_assets(deck.assets))['elements'][0]
        image=Image.open(io.BytesIO(el['_png']))
        self.assertEqual(image.size,(300,750))
        box=image.getbbox()
        self.assertGreater(box[2]-box[0],200)
        self.assertGreater(box[3]-box[1],200)
        self.assertIn(b'width="300" height="750"',el['_svg'])

    def test_raster_formats_are_decoded_and_cropped_identically(self):
        for format, mime in [('JPEG','image/jpeg'),('WEBP','image/webp'),('GIF','image/gif'),('PNG','image/png')]:
            value = deck_input(); value['assets'][0].update(data=raster(format),contentType=mime)
            deck = Deck.model_validate(value)
            assets = decode_assets(deck.assets)
            self.assertEqual(assets['media:7'].size,(200,100))
            el=prepare_scene(deck,deck.slides[0],0,assets)['elements'][0]
            self.assertEqual(Image.open(io.BytesIO(el['_png'])).size,(840,1080))

    def test_missing_or_corrupt_images_fail_before_a_result_exists(self):
        for assets in [[],[{'key':'media:7','contentType':'image/png','data':base64.b64encode(b'not an image').decode()}]]:
            value=deck_input(); value['assets']=assets
            with self.assertRaisesRegex(ValueError, 'PPT_ASSET_INVALID'):
                compile_deck(Deck.model_validate(value))

    def test_svg_rejects_active_external_and_oversized_content(self):
        for svg in ['<svg><script>alert(1)</script></svg>', '<svg><image href="https://x.test/p.png"/></svg>', '<svg><foreignObject/></svg>', '<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg/>', '<svg viewBox="0 0 20 20"><path fill="url(https://x.test)"/></svg>']:
            with self.assertRaisesRegex(ValueError, 'PPT_SVG_INVALID'):
                safe_svg(svg)
        self.assertIn('linearGradient',safe_svg(VECTOR))

    def test_text_overflow_is_actionable_and_never_silently_truncated(self):
        value=deck_input(); value['slides'][0]['elements'][2].update(text='A long piece of information '*15,height=20)
        with self.assertRaisesRegex(ValueError, 'PPT_TEXT_FIT: page 1 element 3 needs'):
            compile_deck(Deck.model_validate(value))

    def test_different_compositions_do_not_get_automatic_cover_decoration(self):
        value=deck_input()
        value['slides'].append({'title':'A different composition','layout':'canvas','background':'#102034','elements':[
            {'type':'text','text':'A centered conclusion','x':180,'y':250,'width':920,'height':120,'fontSize':60,'align':'center','color':'#ffcc88'},
        ]})
        result=compile_deck(Deck.model_validate(value))
        self.assertEqual(len(result['previews']),2)
        self.assertNotEqual(result['previews'][0],result['previews'][1])
        self.assertEqual(len(result['source']['scenes'][1]['elements']),1)


if __name__ == '__main__':
    unittest.main()
