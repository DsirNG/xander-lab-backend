"""Manual QA fixture, not a runtime template. Six independently composed canvas pages."""
import base64
import json
import sys
from pathlib import Path
import resvg_py

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from render import Deck, compile_deck

NAVY, PAPER, INK, ACCENT, MUTED = '#10283F', '#F6F2E9', '#152C3E', '#F3BA7A', '#AAC0CE'
DOG = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 700"><defs><linearGradient id="fur" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f7d9ab"/><stop offset="1" stop-color="#d39154"/></linearGradient></defs><path d="M90 610 Q80 420 190 380 Q195 260 270 250 Q400 230 455 370 Q510 490 455 610Z" fill="url(#fur)"/><path d="M193 380 Q110 325 140 210 Q155 150 243 260Z" fill="#a4693e"/><path d="M350 260 Q450 165 484 225 Q510 305 441 388Z" fill="#a4693e"/><ellipse cx="314" cy="355" rx="111" ry="121" fill="#efd0a1"/><ellipse cx="278" cy="333" rx="9" ry="12" fill="#172b37"/><ellipse cx="359" cy="333" rx="9" ry="12" fill="#172b37"/><ellipse cx="318" cy="385" rx="29" ry="19" fill="#172b37"/><path d="M318 400 Q308 440 280 421 M318 400 Q338 439 362 419" stroke="#70492e" stroke-width="6" fill="none"/><path d="M265 467 Q320 488 380 466" stroke="#dd6d45" stroke-width="23" fill="none"/><path d="M217 512 L210 614 M390 510 L400 615" stroke="#f6d4a1" stroke-width="57" stroke-linecap="round"/><ellipse cx="319" cy="640" rx="207" ry="28" fill="#081c2c" opacity="0.15"/><path d="M158 582 L460 582 L427 652 L190 652Z" fill="#769d9b"/><ellipse cx="309" cy="582" rx="151" ry="25" fill="#b8cfbf"/><ellipse cx="309" cy="582" rx="115" ry="15" fill="#774927"/><path d="M80 570 Q8 535 50 466" stroke="#d39154" stroke-width="34" fill="none" stroke-linecap="round"/></svg>'''
PACK = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 280 400"><path d="M42 20 L238 20 L257 376 L22 376Z" fill="#e6d2ac"/><path d="M42 20 L238 20 L240 63 L39 63Z" fill="#c4a573"/><path d="M32 150 L245 150 L252 300 L26 300Z" fill="#284b4e"/><path d="M115 188 Q140 163 165 188 L173 240 Q140 266 107 240Z" fill="#efbc7d"/><ellipse cx="125" cy="211" rx="4" ry="5" fill="#19343b"/><ellipse cx="155" cy="211" rx="4" ry="5" fill="#19343b"/><path d="M129 227 L151 227 L140 238Z" fill="#19343b"/><path d="M60 330 L218 330 M74 348 L203 348" stroke="#9a895e" stroke-width="7"/></svg>'''
BOWL = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 200"><path d="M32 66 L268 66 L238 155 L63 155Z" fill="#eeac69"/><ellipse cx="150" cy="66" rx="118" ry="30" fill="#f5d6a4"/><ellipse cx="150" cy="66" rx="90" ry="17" fill="#705033"/><path d="M71 122 L230 122" stroke="#fff4dd" stroke-width="6"/></svg>'''


def text(value,x,y,w,h,size=28,color=INK,bold=False,align='left'):
 return dict(type='text',text=value,x=x,y=y,width=w,height=h,fontSize=size,color=color,bold=bold,align=align)
def shape(x,y,w,h,fill,opacity=1,radius=0):
 return dict(type='shape',x=x,y=y,width=w,height=h,fill=fill,opacity=opacity,radius=radius)
def vector(svg,x,y,w,h):
 return dict(type='svg',svg=svg,x=x,y=y,width=w,height=h,fit='contain',alt='产品概念插图')
def page(title,bg,elements):
 return dict(title=title,layout='canvas',background=bg,elements=elements,bullets=[],notes='通用产品方案示例；配方、适用阶段与喂食量应按真实产品标签核对。图片为概念插图。',sources=[])


def example():
 photo=resvg_py.svg_to_bytes(svg_string=DOG,width=600,height=700)
 pages=[]
 pages.append(page('让每一餐，都有清晰的选择依据',NAVY,[
  text('日常营养 / 产品概念方案',68,52,620,40,18,ACCENT),
  shape(728,0,552,720,'#254C52'),
  dict(type='image',assetId=7,x=716,y=28,width=540,height=650,fit='contain',alt='犬与食盆的概念插图'),
  text('让每一餐，\n都有清晰的\n选择依据',68,180,650,300,64,PAPER,True),
  text('从适用阶段、标签信息到日常喂养\n把产品介绍变成可执行的选粮方法',72,530,580,100,27,MUTED),
  text('狗粮产品介绍 · 通用示例',72,667,600,30,16,MUTED),
 ]))
 pages.append(page('先匹配犬的需求，再比较产品卖点',PAPER,[
  text('01 / 产品定位',68,48,700,32,18,'#527778'),
  text('先匹配需求，\n再比较卖点',68,140,540,210,62,INK,True),
  text('面向希望建立稳定喂养习惯的养宠家庭',70,405,480,100,28,'#536873'),
  text('适用信息比宣传口号更先决定选择',70,590,500,70,22,'#527778'),
  shape(644,132,2,480,'#D9D4C6'),
  text('01',700,142,90,60,40,'#B67A42',True),text('生命阶段',808,148,380,54,32,INK,True),
  text('先核对幼犬、成犬或老年犬的适用标识',808,216,350,100,26,'#536873'),
  text('02',700,340,90,60,40,'#B67A42',True),text('个体情况',808,346,380,54,32,INK,True),
  text('结合体况、活动量和既往耐受情况选择',808,414,350,100,26,'#536873'),
 ]))
 pages.append(page('产品价值应能在标签上逐项核对',NAVY,[
  text('02 / 产品价值',68,48,800,32,18,ACCENT),
  text('把包装信息，变成选粮依据',68,108,1140,90,48,PAPER,True),
  vector(PACK,485,240,290,414),
  text('看适用范围',68,270,380,54,32,ACCENT,True),text('适用阶段是否与犬只匹配\n是否标明完整的产品信息',68,342,380,110,26,PAPER),
  text('看配料与营养信息',836,270,390,54,30,ACCENT,True),text('按真实标签说明配料与指标\n不以未经验证的功效代替证据',836,342,360,140,25,PAPER),
  text('看喂养指引',68,512,380,54,32,ACCENT,True),text('是否给出建议喂食量\n以及换粮、保存等说明',68,576,390,100,25,PAPER),
  text('包装为概念示意，不代表实际品牌或配方',832,581,360,65,18,MUTED),
 ]))
 pages.append(page('把日常喂养变成可观察的流程',PAPER,[
  text('03 / 使用流程',68,48,800,32,18,'#527778'),
  text('建立稳定习惯，也留出观察窗口',68,112,1140,90,48,INK,True),
  shape(90,296,1080,3,'#C3CFC8'),
  *[shape(88+i*290,281,34,34,'#B67A42',radius=17) for i in range(4)],
  *[text(str(i+1),88+i*290,283,34,30,18,PAPER,True,'center') for i in range(4)],
  *[text(v,68+i*290,359,250,60,30,INK,True) for i,v in enumerate(['核对标签','安排换粮','规律喂养','持续观察'])],
  *[text(v,68+i*290,446,244,150,25,'#536873') for i,v in enumerate(['确认适用阶段\n阅读喂养建议','按产品指引\n逐步建立新习惯','结合建议喂食量\n保持饮水与作息','记录采食与体况\n异常时咨询兽医'])],
  text('具体换粮周期与喂食量，以真实产品说明及专业建议为准',68,650,1140,40,20,'#527778'),
 ]))
 pages.append(page('产品如何进入真实养宠生活',NAVY,[
  text('04 / 使用场景',68,48,800,32,18,ACCENT),
  text('围绕生活节奏，\n让喂养更容易坚持',68,134,660,180,54,PAPER,True),
  vector(BOWL,68,400,420,230),
  shape(696,112,2,504,'#385364'),
  text('日常正餐',760,138,420,52,32,ACCENT,True),text('固定地点与时段，按需安排食量',760,210,420,80,25,PAPER),
  text('外出与寄养',760,329,420,52,32,ACCENT,True),text('提前分装并标注喂养习惯\n让照护者延续熟悉的流程',760,401,420,110,25,PAPER),
  text('特殊健康需求不以通用狗粮方案替代诊疗建议',760,589,420,70,20,MUTED),
 ]))
 pages.append(page('从一份清晰的喂养方案开始',ACCENT,[
  text('05 / 总结与下一步',68,48,800,32,18,'#69482C'),
  text('好的产品介绍，\n让选择和使用都更清楚',68,127,1140,180,60,INK,True),
  *[text(v,76,366+i*75,1120,60,29,INK) for i,v in enumerate(['01   选得对：先核对适用信息，再比较产品差异','02   喂得稳：依照标签建立可执行的日常流程','03   看得见：持续观察，及时调整照护方式'])],
  shape(68,627,1144,2,'#C78E56'),
  text('下一步：补充真实品牌、配方与标签，即可形成正式产品介绍',68,653,1144,44,23,INK,True),
 ]))
 return dict(title='狗粮产品介绍 · 自由构图示例',theme='navy',language='zh-CN',slides=pages,assets=[dict(key='media:7',contentType='image/png',data=base64.b64encode(photo).decode())])


if __name__=='__main__':
 output=Path(sys.argv[1]);output.mkdir(parents=True,exist_ok=True)
 value=example(); (output/'request.json').write_text(json.dumps(value,ensure_ascii=False),encoding='utf-8')
 result=compile_deck(Deck.model_validate(value))
 (output/'showcase.pptx').write_bytes(base64.b64decode(result['pptx']))
 for i,png in enumerate(result['previews']): (output/f'page-{i+1}.png').write_bytes(base64.b64decode(png))
 from PIL import Image,ImageOps,ImageDraw
 montage=Image.new('RGB',(1440,1260),'#e6e5e2')
 for i in range(6):
  image=Image.open(output/f'page-{i+1}.png');image.thumbnail((696,392));montage.paste(image,(12+(i%2)*720,12+(i//2)*420))
 montage.save(output/'montage.png')
 print(json.dumps({'pages':len(result['previews']),'quality':result['quality'],'output':str(output)},ensure_ascii=False))
