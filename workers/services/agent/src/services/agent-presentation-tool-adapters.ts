import type { PresentationPort } from "../interfaces/agent-presentation.js";
import type { AgentToolDefinition } from "./agent-tool-registry.js";
import type { AgentTool } from "./agent-tool-runtime.js";
import { AgentToolExecutionError } from "./agent-tool-runtime.js";
import { PresentationError } from "./agent-presentation-validation.js";
import { preparePresentationDeck } from "./agent-presentation-preparation.js";

const slide = { type: "object", properties: {
  title: { type: "string", minLength: 1, maxLength: 70 },
  layout: { type: "string", enum: ["cover", "bullets", "columns", "cards", "process", "comparison", "chart", "closing"], description: "按信息关系选版式：cover封面；cards功能/场景模块；process有先后顺序的步骤；comparison两种方案对比；chart有依据的定量比较；closing结论与行动；bullets/columns为纯文字兼容布局。避免连续页面重复一种结构。" },
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
  { name: "create_presentation", description: "制作用户下载后仅需微调文案的专业PPT。先确定受众、目的和一条主叙事；每页标题优先写结论，正文给具体机制、案例、交付物或行动，不堆空泛口号。使用用户资料，公开事实缺依据时先搜索阅读；不得虚构品牌事实、性能数字、客户或来源。不确定事项在备注中明确标注待确认。按内容关系安排封面、模块、流程、对比、真实数据图表、结尾行动等版式，统一主题且保持页面节奏；详细解释放演讲备注。提交持久化异步任务后只报告任务已保存，查询完成才可声称可下载。文字和图形导出为原生可编辑对象。最多20页，不接受任意代码或SVG。",
    parameters: { type: "object", properties: {
      title: { type: "string", maxLength: 100 }, language: { type: "string", enum: ["zh-CN", "en-US"] },
      theme: { type: "string", enum: ["navy", "paper"] }, slides: { type: "array", minItems: 1, maxItems: 20, items: slide },
    }, required: ["title", "slides"], additionalProperties: false } },
  { name: "get_presentation", description: "查询当前用户的 PPT 任务、页面结构、文件和版本。修改单页前先读取，保留该页来源与备注；不要猜测任务ID或版本号。", parameters: {
    type: "object", properties: { id: { type: "string" }, version: { type: "integer", minimum: 1 } }, required: ["id"], additionalProperties: false,
  } },
  { name: "revise_presentation_slide", description: "用户明确要求修改某页PPT时调用。仅替换指定页并生成新版本，其余页内容保持一致。先查询实际任务、当前版本和页内容；slideIndex从0开始。", parameters: {
    type: "object", properties: { id: { type: "string" }, expectedVersion: { type: "integer", minimum: 1 },
      slideIndex: { type: "integer", minimum: 0, maximum: 19 }, slide }, required: ["id", "expectedVersion", "slideIndex", "slide"], additionalProperties: false,
  } },
];

export function createPresentationTools(service: PresentationPort, emit: (event: string, data: Record<string, unknown>) => Promise<void>): Map<string, AgentTool> {
  return new Map(PRESENTATION_TOOLS.map(definition => [definition.name, {
    definition: () => ({ ...definition, readOnly: definition.name === "get_presentation" }),
    async execute(context, input) {
      context.signal.throwIfAborted();
      const identity = { tool: definition.name, invocationId: context.invocationId };
      await emit("tool_start", { ...identity, startedAt: new Date().toISOString() });
      try {
        const prepared = definition.name === "create_presentation" ? preparePresentationDeck(input) : null;
        const data = definition.name === "create_presentation"
          ? await service.create(context.userId, context.conversationId, context.idempotencyKey, prepared!.deck)
          : await service.action(context.userId, String(input.id ?? ""), definition.name === "get_presentation" ? "get" : "revise",
            { ...input, requestId: context.idempotencyKey.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 100) });
        const answer = data.status === "completed"
          ? "PPT 已生成，可以在卡片中预览或下载。"
          : data.status === "failed"
            ? `PPT 任务失败，原因：${data.error ?? "未知渲染错误"}。请读取失败原因，调整页面结构或内容后调用 revise_presentation_slide 重试。`
            : "PPT 任务已保存，可在卡片中查看进度、预览和下载。";
        return { ok: true, data, answer: answer
          + (prepared?.adjustedSlides ? " 已自动适配页面布局，缩略的完整内容保留在演讲备注中。" : "") };
      } catch (error) {
        const message = error instanceof PresentationError ? error.message : "PPT 服务暂不可用";
        await emit("tool_error", { ...identity, error: message });
        throw new AgentToolExecutionError("PRESENTATION_FAILED", message, true);
      }
    },
  } satisfies AgentTool]));
}
