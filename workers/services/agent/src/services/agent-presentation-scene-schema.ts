const color = { type: "string", pattern: "^#[a-fA-F0-9]{6}$" };
export const presentationPaletteSchema = {
  type: "object", additionalProperties: false,
  properties: { background: color, foreground: color, muted: color, accent: color, surface: color },
  required: ["background", "foreground", "muted", "accent", "surface"],
};
export const presentationElementsSchema = {
  type: "array", maxItems: 200,
  description: "自由构图，1280×720坐标；按数组顺序从底层到顶层。每页根据内容决定图片、SVG、原生文字和形状，无固定模板。文字与背景分开以便编辑。canvas页面必须把全部可见内容写在elements；title只作为目录标题。",
  items: { type: "object", additionalProperties: false, required: ["type", "x", "y", "width", "height"], properties: {
    type: { type: "string", enum: ["text", "shape", "image", "svg"] },
    x: { type: "number", minimum: 0, maximum: 1280 }, y: { type: "number", minimum: 0, maximum: 720 },
    width: { type: "number", minimum: 1, maximum: 1280 }, height: { type: "number", minimum: 1, maximum: 720 },
    text: { type: "string", maxLength: 4000 }, fontSize: { type: "number", minimum: 8, maximum: 160, description: "画布像素字号，正文通常24至30，标题44至72，按实际构图决定" },
    fontFamily: { type: "string", enum: ["Noto Sans CJK SC", "Noto Serif CJK SC", "Noto Sans Mono CJK SC"], description: "按场景选择字体：无衬线适合产品与商务，衬线适合文化与叙事，等宽适合代码与参数；标题与正文可分别选择，保持层级统一" },
    color, bold: { type: "boolean" }, align: { type: "string", enum: ["left", "center", "right"] },
    fill: color, stroke: color, strokeWidth: { type: "number", minimum: 0, maximum: 30 },
    shape: { type: "string", enum: ["rect", "ellipse", "line"] }, radius: { type: "number", minimum: 0, maximum: 360 },
    opacity: { type: "number", minimum: 0, maximum: 1 },
    attachmentId: { type: "integer", minimum: 1, description: "用户上传的图片附件ID，与assetId二选一；不可猜测" },
    assetId: { type: "integer", minimum: 1, description: "image_generate或素材查询返回的已保存图片素材ID，与attachmentId二选一；等待图片保存成功再引用" },
    svg: { type: "string", maxLength: 32000, description: "自包含SVG插图，含viewBox；允许path、基本图形、渐变、文字、分组。禁止脚本、foreignObject、外部链接、图片和网络资源。适合图标、关系图、抽象插图，不用无关圆环代替主题配图" },
    alt: { type: "string", maxLength: 300, description: "图片或插图的内容说明" },
    fit: { type: "string", enum: ["cover", "contain"], description: "cover居中裁切填满，contain完整显示；产品包装和证据截图优先contain" },
  } },
};
