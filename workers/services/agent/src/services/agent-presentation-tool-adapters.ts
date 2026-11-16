import type { PresentationPort } from "../interfaces/agent-presentation.js";
import type { AgentToolDefinition } from "./agent-tool-registry.js";
import type { AgentTool } from "./agent-tool-runtime.js";
import { AgentToolExecutionError } from "./agent-tool-runtime.js";
import { PresentationError } from "./agent-presentation-validation.js";
import { preparePresentationDeck } from "./agent-presentation-preparation.js";
import { presentationElementsSchema, presentationPaletteSchema } from "./agent-presentation-scene-schema.js";
import { AgentImageError, type AgentImageService } from "./agent-image-service.js";

const slide = { type: "object", properties: {
  title: { type: "string", minLength: 1, maxLength: 70 },
  layout: { type: "string", enum: ["canvas", "cover", "bullets", "columns", "cards", "process", "comparison", "chart", "closing"], description: "专业设计优先canvas，自由安排图片、SVG、文字与形状。其余为旧文稿兼容布局。根据内容选择构图，保持整套风格一致和页面节奏。" },
  background: { type: "string", pattern: "^#[a-fA-F0-9]{6}$", description: "本页背景，可与整套配色不同" },
  elements: presentationElementsSchema,
  kicker: { type: "string", maxLength: 28, description: "简短章节标签，如产品定位、工作流程；使用文稿语言" },
  subtitle: { type: "string", maxLength: 80, description: "说明本页背景或支撑标题的关键判断，避免重复标题" },
  takeaway: { type: "string", maxLength: 100, description: "本页读者应该记住的结论或可执行下一步" },
  blocks: { type: "array", items: { type: "object", properties: {
    heading: { type: "string", maxLength: 32 }, body: { type: "string", maxLength: 100 },
  }, required: ["heading", "body"], additionalProperties: false }, description: "内容模块。模块数量和页面组织由 Agent 根据主题自行决定。" },
  chart: { type: "object", properties: {
    unit: { type: "string", maxLength: 12 }, caption: { type: "string", maxLength: 100, description: "数据口径、时间和出处；示例数据须明确标注，不能伪装成真实结果" },
    points: { type: "array", items: { type: "object", properties: {
      label: { type: "string", maxLength: 24 }, value: { type: "number", minimum: 0, maximum: 1000000000000 },
    }, required: ["label", "value"], additionalProperties: false } },
  }, required: ["unit", "caption", "points"], additionalProperties: false, description: "仅chart版式使用，bullets为空；只使用用户提供或已核实的数据，没有数据就选定性版式" },
  bullets: { type: "array", items: { type: "string", maxLength: 120 }, description: "页面要点。数量和组织方式由 Agent 根据主题自行决定，较长解释可放入 notes。" },
  notes: { type: "string", maxLength: 2000, description: "演讲备注，可放长解释；不把长段落挤入页面" },
  sources: { type: "array", items: { type: "object", properties: {
    title: { type: "string", maxLength: 120 }, url: { type: "string", maxLength: 2000 },
  }, required: ["title", "url"], additionalProperties: false }, description: "仅传实际阅读或用户提供的来源；禁止虚构链接" },
}, required: ["title", "layout", "bullets"], additionalProperties: false };

export const PRESENTATION_TOOLS: AgentToolDefinition[] = [
  { name: "create_presentation", description: "制作用户下载后仅需微调文案的专业PPT。先确定受众、目的和一条主叙事，再写大纲；6页产品介绍优先采用：1封面与核心主张、2用户痛点与产品定位、3产品如何工作、4核心功能分组、5真实使用场景或案例、6价值总结与下一步。每页标题写结论，正文给具体机制、案例、交付物或行动，不堆空泛口号。使用用户资料，公开事实缺依据时先搜索阅读；不得虚构品牌事实、性能数字、客户或来源。不确定事项在备注中明确标注待确认。按内容关系安排封面、模块、流程、对比、图表、结尾等版式，统一主题且保持页面节奏；详细解释放演讲备注。提交持久化异步任务后只报告任务已保存，只有查询确认 status=completed 且 files.pptx 与 files.previews 齐全时才可展示完成、图片或下载链接。专业设计采用canvas自由构图，Agent自行决定配色、留白、字号、图片与插图，不要把所有主题套成圆环封面和卡片网格。需要照片、产品氛围或背景时先调用prepare_presentation_image或查询用户素材，等待保存完成后用assetId引用；用户上传图片用attachmentId。没有真实产品资料时不得把生成包装伪装为真实产品，可明确作为概念插图。图标、关系图和抽象插图可直接编写安全的自包含SVG；文字单独用text元素，基础图形用shape以保持可编辑。页面应围绕主题使用不同构图，深蓝只决定色调，不决定布局。canvas页面全部可见内容由elements定义。提交前检查每页信息与画布，完成后读取预览图片检查效果，有问题修改后重生成。文字和基础图形为原生可编辑对象，照片为可替换图片，SVG保留矢量与PNG兼容回退。最多20页，不接受可执行代码。",
    parameters: { type: "object", properties: {
      title: { type: "string", maxLength: 100 }, language: { type: "string", enum: ["zh-CN", "en-US"] },
      theme: { type: "string", enum: ["navy", "paper"], description: "兼容主题默认值，不限制palette和每页的自定义构图" }, palette: presentationPaletteSchema,
      slides: { type: "array", minItems: 1, maxItems: 20, items: slide },
    }, required: ["title", "slides"], additionalProperties: false } },
  { name: "get_presentation", description: "查询当前用户的 PPT 任务、页面结构、文件和版本。waitMs可等待本次异步渲染，未完成时继续查询；完成后查看返回的实际预览图片，检查内容和视觉效果，有问题修改页面。修改单页前先读取，保留该页来源与备注；不要猜测任务ID或版本号。", parameters: {
    type: "object", properties: { id: { type: "string" }, version: { type: "integer", minimum: 1 }, waitMs: { type: "integer", minimum: 0, maximum: 20000 } }, required: ["id"], additionalProperties: false,
  } },
  { name: "revise_presentation_slide", description: "用户要求修改或Agent审查实际预览发现问题时调用。仅替换指定页并生成新版本，其余页内容保持一致。先查询实际任务、当前版本和页内容；slideIndex从0开始。", parameters: {
    type: "object", properties: { id: { type: "string" }, expectedVersion: { type: "integer", minimum: 1 },
      slideIndex: { type: "integer", minimum: 0, maximum: 19 }, slide }, required: ["id", "expectedVersion", "slideIndex", "slide"], additionalProperties: false,
  } },
  { name: "retry_presentation", description: "查询确认PPT失败或取消后，若是暂时的网络、素材读取或渲染服务故障，重试原设计；如果需要改内容或构图，使用revise_presentation_slide。使用查询得到的实际ID和当前版本。", parameters: {
    type: "object", properties: { id: { type: "string" }, expectedVersion: { type: "integer", minimum: 1 } }, required: ["id", "expectedVersion"], additionalProperties: false,
  } },
];

export function createPresentationTools(service: PresentationPort, emit: (event: string, data: Record<string, unknown>) => Promise<void>, images?: AgentImageService): Map<string, AgentTool> {
  const definitions = [...PRESENTATION_TOOLS];
  if (images) definitions.push({ name: "prepare_presentation_image", description: "为演示文稿生成并保存照片风格插图、产品概念图或背景。仅在用户授权制作或美化PPT且需要新图时调用。等待保存完成，返回真实assetId后继续制作PPT；此工具不会结束制作流程。不要把概念图宣称为真实产品照片。提示词中指定构图、画幅、留白、风格；通常不让图片承担正文文字。",
    parameters: { type: "object", additionalProperties: false, required: ["prompt"], properties: {
      prompt: { type: "string", minLength: 1, maxLength: 2000 }, title: { type: "string", maxLength: 120 },
    } } });
  return new Map(definitions.map(definition => [definition.name, {
    definition: () => ({ ...definition, readOnly: definition.name === "get_presentation" }),
    async execute(context, input) {
      context.signal.throwIfAborted();
      const identity = { tool: definition.name, invocationId: context.invocationId };
      await emit("tool_start", { ...identity, startedAt: new Date().toISOString() });
      try {
        if (definition.name === "prepare_presentation_image") {
          const data = await images!.generate(context.userId, context.conversationId, context.runVersion, input,
            undefined, async () => { context.signal.throwIfAborted(); });
          return { ok: true, data, answer: "图片已完整保存，请使用返回的assetId继续构图并创建PPT。" };
        }
        const prepared = definition.name === "create_presentation" ? preparePresentationDeck(input) : null;
        let data = definition.name === "create_presentation"
          ? await service.create(context.userId, context.conversationId, context.idempotencyKey, prepared!.deck)
          : await service.action(context.userId, String(input.id ?? ""), definition.name === "get_presentation" ? "get" : definition.name === "retry_presentation" ? "retry" : "revise",
            { ...input, requestId: context.idempotencyKey.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 100) });
        if (definition.name === "get_presentation") {
          const duration = typeof input.waitMs === "number" && Number.isFinite(input.waitMs) ? Math.min(20000, Math.max(0, input.waitMs)) : 0;
          const deadline = Date.now() + duration;
          while (["queued", "rendering", "uploading"].includes(data.status) && Date.now() < deadline) {
            await new Promise(resolve => setTimeout(resolve, Math.min(1000, deadline - Date.now())));
            context.signal.throwIfAborted();
            data = await service.action(context.userId, String(input.id ?? ""), "get", { version: input.version });
          }
        }
        const answer = data.status === "completed"
          ? "PPT 已生成，可以在卡片中预览或下载。"
          : data.status === "failed"
            ? `PPT 任务失败，原因：${data.error ?? "未知渲染错误"}。请判断原因：内容或构图问题使用 revise_presentation_slide 修改，暂时性服务问题使用 retry_presentation；完成后读取实际预览继续检查。`
            : "PPT 任务已保存，可在卡片中查看进度、预览和下载。";
        return { ok: true, data, answer: answer
          + (prepared?.adjustedSlides ? " 已自动适配页面布局，缩略的完整内容保留在演讲备注中。" : "") };
      } catch (error) {
        const message = (error instanceof PresentationError || error instanceof AgentImageError) ? error.message : "PPT 服务暂不可用";
        await emit("tool_error", { ...identity, error: message });
        throw new AgentToolExecutionError("PRESENTATION_FAILED", message, true);
      }
    },
  } satisfies AgentTool]));
}
