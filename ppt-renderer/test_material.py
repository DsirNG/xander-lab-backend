import base64
import io
import unittest
from unittest.mock import patch
from PIL import Image
from normalize_image import normalize_image
from page_scene import font
from render import Deck, compile_deck
from pptx import Presentation

class MaterialTests(unittest.TestCase):
    def test_source_images_are_decoded_and_svg_axes_are_bounded_before_rasterizing(self):
        data=io.BytesIO();Image.new('RGB',(32,16),'red').save(data,format='JPEG')
        result=normalize_image({'contentType':'image/jpeg','data':base64.b64encode(data.getvalue()).decode()})
        self.assertEqual((result['width'],result['height']),(32,16))
        with patch('normalize_image.resvg_py.svg_to_bytes',return_value=base64.b64decode(result['data'])) as raster:
            normalize_image({'contentType':'image/svg+xml','data':base64.b64encode(b'<svg viewBox="0 0 .001 10000"><rect width=".001" height="10000"/></svg>').decode()})
            self.assertLessEqual(raster.call_args.kwargs['width'],1600)
            self.assertLessEqual(raster.call_args.kwargs['height'],1600)
        with self.assertRaises(ValueError):
            normalize_image({'contentType':'image/svg+xml','data':base64.b64encode(b'<svg><script>bad()</script></svg>').decode()})
    def test_selected_font_is_measured_and_exported_as_editable_text(self):
        self.assertNotEqual(font(36,family='Noto Sans CJK SC').getlength('WWWiii'),font(36,family='Noto Sans Mono CJK SC').getlength('WWWiii'))
        self.assertNotEqual(bytes(font(36,family='Noto Sans CJK SC').getmask('永')),bytes(font(36,family='Noto Serif CJK SC').getmask('永')))
        deck=Deck.model_validate({'title':'Fonts','slides':[{'title':'Editorial','layout':'canvas','elements':[{'type':'text','text':'文字可以修改','x':50,'y':50,'width':700,'height':200,'fontFamily':'Noto Serif CJK SC','fontSize':48}]}]})
        result=compile_deck(deck)
        prs=Presentation(io.BytesIO(base64.b64decode(result['pptx'])))
        runs=[run for shape in prs.slides[0].shapes if shape.has_text_frame for para in shape.text_frame.paragraphs for run in para.runs]
        self.assertTrue(runs);self.assertEqual(runs[0].font.name,'Noto Serif CJK SC')
