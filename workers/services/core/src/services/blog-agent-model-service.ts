import type {
  BlogAgentModelPort,
  BlogAgentModelResponse,
  BlogAgentModelStage,
} from "../interfaces/blog-agent-model.js";

export class BlogAgentModelError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "BlogAgentModelError";
  }
}

type StageInput = Record<string, unknown>;

const ARTICLE_INSTRUCTIONS = "你是 DinQorAI 的知识博客生成助手。把用户给出的主题、日记或项目问题，写成可发布的中文 Markdown 知识博客。"
  + "文章主干必须来自用户明确提到的主题、问题、场景与目标；你必须自行判断输入性质、合适读者与写作语气。"
  + "只扩展直接帮助理解、验证或解决用户核心问题的知识，不要为了套模板强行加入无关内容。"
  + "先澄清文章角度，再使用网页搜索工具核验或补充外部事实；没有可靠来源时不得写成确定事实。"
  + "输出必须且仅是 JSON，包含 title、summary、content、outline、writingBrief、contentBoundary、knowledgeGraph、categoryId、tags、review、illustrations、sources。"
  + "content 必须是完整 Markdown 文章，并以“## 参考资料”列出已检索且实际使用的来源；不需要插图时 illustrations 返回空数组。"
  + "categoryId 只可使用 frontend、backend、architecture、devops、career。";

const STAGES: Record<BlogAgentModelStage, (input: StageInput) => { instruction: string; content: string; webSearch?: boolean }> = {
  analyze: (input) => ({
    instruction: "你是知识博客策划编辑。只分析用户真正想写什么、必须保留什么、哪些信息需要查证。不要写文章，不要搜索。仅返回 JSON：{\"angle\":string,\"inputNature\":string,\"audience\":string,\"mustCover\":[string],\"questions\":[string],\"researchQueries\":[string]}。",
    content: String(input.input),
  }),
  research: (input) => ({
    instruction: "你是知识博客研究员。使用网页搜索核验和补全用户主题，只保留与文章主线直接相关的权威信息。不要写文章。仅返回 JSON：{\"findings\":[{\"claim\":string,\"evidence\":string,\"url\":string,\"publisher\":string}],\"gaps\":[string],\"recommendedStructure\":[string]}。",
    content: `用户输入：\n${String(input.input)}\n\n策划结果：\n${String(input.analysisJson)}`,
    webSearch: true,
  }),
  article: (input) => ({ instruction: ARTICLE_INSTRUCTIONS, content: `用户输入：\n${String(input.input)}`, webSearch: true }),
  review: (input) => ({
    instruction: "你是严谨的中文知识博客审校编辑。检查逻辑、事实表述、结构、Markdown 和用户要求。保留图片 Markdown，不要虚构来源。仅返回 JSON：{\"content\":string,\"review\":string}。",
    content: `本轮用户要求：\n${String(input.request)}\n\n待审校文章：\n${String(input.content)}`,
  }),
  revise: (input) => ({
    instruction: `${ARTICLE_INSTRUCTIONS}这是同一会话中的后续修改。必须以当前文章为基础执行用户要求，不要无故重写未涉及部分。仍返回完整 JSON 文章对象。`,
    content: `当前文章：\n${String(input.content)}\n\n用户本轮修改要求：\n${String(input.instruction)}`,
  }),
  compliance: (input) => ({
    instruction: "你是内容安全审核员。判断文章是否包含色情、赌博、毒品、暴力仇恨、诈骗、违法违规等不得发布的敏感违规内容，或极低质量的水文。不要修改正文。仅返回 JSON：{\"pass\":boolean,\"reasons\":[string]}。pass 表示可正常发布；不通过时 reasons 给出具体理由，通过时返回空数组。",
    content: `标题：\n${String(input.title)}\n\n正文：\n${String(input.content)}`,
  }),
};

const REQUIRED_FIELDS: Record<BlogAgentModelStage, string[]> = {
  analyze: ["input"], research: ["input", "analysisJson"], article: ["input"],
  review: ["content", "request"], revise: ["content", "instruction"], compliance: ["title", "content"],
};

function validStage(value: string): value is BlogAgentModelStage {
  return Object.hasOwn(STAGES, value);
}

export class BlogAgentModelService {
  constructor(private readonly model: BlogAgentModelPort, private readonly webSearchEnabled = false) {}

  async generateTopics(input: StageInput): Promise<{ topics: string[] }> {
    const seed = input.seed;
    const count = input.count;
    const audience = input.audience;
    const tone = input.tone;
    const excluded = input.excludeTitles;
    if (typeof seed !== "string" || !seed.trim() || seed.length > 500
      || !Number.isSafeInteger(count) || (count as number) < 1 || (count as number) > 30
      || audience !== undefined && (typeof audience !== "string" || audience.length > 120)
      || tone !== undefined && (typeof tone !== "string" || tone.length > 60)
      || excluded !== undefined && (!Array.isArray(excluded) || excluded.length > 200
        || excluded.some((value) => typeof value !== "string" || value.length > 500))) {
      throw new BlogAgentModelError(400, "博客选题请求参数无效");
    }
    const excludeTitles = Array.isArray(excluded) ? excluded as string[] : [];
    const instruction = `你是知识博客策划编辑。围绕用户给出的主题种子，细化出 ${count as number}`
      + " 个彼此独立、具体可写、适合定时发布的中文知识博客标题。"
      + "每个标题必须与主题种子高度相关但角度不同，避免空洞泛题。"
      + (excludeTitles.length ? `\n以下主题已使用过，禁止重复：\n${excludeTitles.join("\n")}\n` : "\n")
      + "不要写文章。仅返回 JSON：{\"topics\":[string]，数量严格等于要求}。";
    const content = `主题种子：\n${seed.trim()}`
      + (typeof audience === "string" && audience.trim() ? `\n目标读者：\n${audience.trim()}` : "")
      + (typeof tone === "string" && tone.trim() ? `\n文风语气：\n${tone.trim()}` : "");
    let result: BlogAgentModelResponse;
    try {
      result = await this.model.complete({ instruction, input: content, webSearch: false });
    } catch {
      throw new BlogAgentModelError(503, "博客模型服务暂不可用");
    }
    let payload: unknown;
    try { payload = JSON.parse(result.payloadJson); }
    catch { throw new BlogAgentModelError(502, "博客模型选题结果格式无效"); }
    const rawTopics = payload && typeof payload === "object" && !Array.isArray(payload)
      ? (payload as Record<string, unknown>).topics : null;
    if (!Array.isArray(rawTopics)) throw new BlogAgentModelError(502, "博客模型选题结果格式无效");
    const seen = new Set([seed, ...excludeTitles].map((value) => value.trim().toLocaleLowerCase()));
    const topics: string[] = [];
    for (const value of rawTopics) {
      if (typeof value !== "string") continue;
      const topic = value.trim();
      const key = topic.toLocaleLowerCase();
      if (!topic || topic.length > 500 || seen.has(key)) continue;
      seen.add(key);
      topics.push(topic);
      if (topics.length === (count as number)) break;
    }
    return { topics };
  }

  async execute(stageValue: string, input: StageInput): Promise<BlogAgentModelResponse> {
    if (!validStage(stageValue)) throw new BlogAgentModelError(404, "Not Found");
    for (const field of REQUIRED_FIELDS[stageValue]) {
      if (typeof input[field] !== "string" || !(input[field] as string).trim()) {
        throw new BlogAgentModelError(400, "博客模型请求参数无效");
      }
    }
    if (stageValue === "research") {
      try { input.analysisJson = JSON.stringify(JSON.parse(input.analysisJson as string)); }
      catch { throw new BlogAgentModelError(400, "博客模型研究阶段参数无效"); }
    }
    const prompt = STAGES[stageValue](input);
    try {
      return await this.model.complete({
        instruction: prompt.instruction,
        input: prompt.content,
        webSearch: Boolean(this.webSearchEnabled && prompt.webSearch),
      });
    } catch {
      throw new BlogAgentModelError(503, "博客模型服务暂不可用");
    }
  }
}
