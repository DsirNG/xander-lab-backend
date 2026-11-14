import type { AgentToolDefinition } from "./agent-tool-registry.js";

export type AgentFunctionTool = AgentToolDefinition;

export const MEMORY_TOOLS: AgentFunctionTool[] = [
  {
    name: "remember",
    description: "把跨会话长期有效的信息写入自己的长期记忆，或删除已失效的记忆。只记用户的身份、稳定偏好、长期目标或明确要求记住的事；也可记住用户明确指定的助手名字、外貌和自身形象照引用。保存形象照时使用 assistant-portrait 作为 memoryKey，content 记录真实已知的 assetId（例如 assetId: 123），外貌文字不能当作已有照片；不要猜测 ID。不要记临时细节、密码、密钥或可从内容服务查询的数据。memoryKey 使用稳定英文短横线命名；复用同一个 key 会更新。",
    parameters: {
      type: "object",
      properties: {
        action: { type: "string", enum: ["upsert", "delete"], description: "写入/更新或删除；默认 upsert" },
        memoryKey: { type: "string", maxLength: 120, description: "稳定记忆标识，例如 writing-style" },
        kind: { type: "string", enum: ["PROFILE", "PREFERENCE", "FACT", "PROJECT"] },
        content: { type: "string", maxLength: 2000, description: "单条长期记忆正文；删除时省略" },
      },
      required: ["memoryKey"],
      additionalProperties: false,
    },
  },
  {
    name: "recall_memory",
    description: "按关键词查找自己的长期记忆。通常记忆已自动注入；仅在用户追问更早的具体事实或需确认某条记忆时调用。",
    parameters: {
      type: "object",
      properties: {
        query: { type: "string", description: "关键词或主题；留空时返回最近更新的记忆" },
        limit: { type: "integer", minimum: 1, maximum: 8 },
      },
      additionalProperties: false,
    },
  },
];

export const RECITATION_TOOLS: AgentFunctionTool[] = [
  {
    name: "query_knowledge",
    description: "查询当前用户自己的个人知识库、掌握度和原文摘录。涉及用户已保存的知识时先查这里；只返回用户本人的记录。",
    parameters: {
      type: "object",
      properties: {
        search: { type: "string", maxLength: 300, description: "按标题或正文搜索，可选" },
        masteryLevel: { type: "string", enum: ["NEW", "FAMILIAR", "RECALLING", "APPLYING", "MASTERED"] },
        dueOnly: { type: "boolean", description: "仅查已到复习时间的记录" },
        fullContent: { type: "boolean", description: "返回较长正文摘录，最多 3 条" },
        limit: { type: "integer", minimum: 1, maximum: 30 },
      },
      additionalProperties: false,
    },
  },
  {
    name: "create_knowledge",
    description: "把用户明确要求保存或加入个人知识库的内容写入知识库。只有用户明确要求保存时才调用，不得擅自保存普通聊天内容。",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", maxLength: 100 },
        content: { type: "string", maxLength: 10_000 },
        knowledgeType: { type: "string", enum: ["RECITATION", "CONCEPT", "MATH"] },
      },
      required: ["title", "content", "knowledgeType"],
      additionalProperties: false,
    },
  },
  {
    name: "start_knowledge_quiz",
    description: "仅在用户明确要求复习或测验其个人知识时调用。按材料内容生成一组不附答案的题目，并把测验绑定到当前对话。",
    parameters: {
      type: "object",
      properties: {
        materialId: { type: "integer", minimum: 1, description: "当前用户个人知识库中的材料 ID" },
        questionCount: { type: "integer", minimum: 3, maximum: 10, description: "题目数量，默认 5" },
      },
      required: ["materialId"],
      additionalProperties: false,
    },
  },
  {
    name: "record_knowledge_quiz",
    description: "用户提交当前对话中的测验答案后调用。逐题记录原题、用户原答、简短评语和基于材料的 0 到 1 得分；不得补记用户未回答的题。",
    parameters: {
      type: "object",
      properties: {
        quizSessionId: { type: "integer", minimum: 1, description: "start_knowledge_quiz 返回的会话 ID" },
        verdict: { type: "string", maxLength: 1000, description: "整体反馈" },
        items: {
          type: "array", minItems: 1, maxItems: 20,
          items: {
            type: "object",
            properties: {
              question: { type: "string", maxLength: 500 },
              userAnswer: { type: "string", maxLength: 1000 },
              comment: { type: "string", maxLength: 500 },
              credit: { type: "number", minimum: 0, maximum: 1 },
            },
            required: ["question", "userAnswer", "comment", "credit"],
            additionalProperties: false,
          },
        },
      },
      required: ["quizSessionId", "items"],
      additionalProperties: false,
    },
  },
];

export const QUIZ_TOOLS: AgentFunctionTool[] = [{
  name: "emit_quiz",
  description: "把题目发成可点选、可翻页并一次提交的答题卡。用户要求出题、测验或练习时使用；题目不要写入回复正文。来自 start_knowledge_quiz 时原样传入 quizSessionId；普通练习省略。答题卡发出后用一句话请用户在卡片作答。",
  parameters: {
    type: "object",
    properties: {
      quizSessionId: { type: "integer", minimum: 1, description: "可选，start_knowledge_quiz 返回的测验会话 ID" },
      title: { type: "string", maxLength: 60, description: "可选的答题卡标题" },
      questions: {
        type: "array", minItems: 1, maxItems: 10,
        items: {
          type: "object",
          properties: {
            prompt: { type: "string", maxLength: 500 },
            type: { type: "string", enum: ["single", "multiple", "text"] },
            options: { type: "array", maxItems: 8, items: { type: "string", maxLength: 200 } },
            correctAnswers: { type: "array", maxItems: 8, items: { type: "string", maxLength: 200 }, description: "Choice questions: privately verify the correct option text here. Every correct answer must exist in options. This field is removed before the card reaches the user." },
          },
          required: ["prompt", "type", "correctAnswers"],
          additionalProperties: false,
        },
      },
    },
    required: ["questions"],
    additionalProperties: false,
  },
}];

export const ARTIFACT_TOOLS: AgentFunctionTool[] = [{
  name: "emit_artifact",
    description: "把用户明确要求的文件作为对话内交付卡发给用户，文件可预览、复制或下载。支持 Markdown、HTML、TXT、CSV、JSON、DOCX、PDF；用户明确要求 Word/PDF 时使用对应 format，并把完整文章正文放在 content 中，由服务端将 Markdown 渲染为有结构的真实 Word/PDF 文件并上传后交付；支持多个文档，每个文档最多 200000 字符，普通代码文件最多 20000 字符。普通解释、单个短示例和小修复直接写在回复中。每个文件必须提供完整内容，最多 12 个文件。",
  parameters: {
    type: "object",
    properties: {
      name: { type: "string", maxLength: 60 },
      kind: { type: "string", enum: ["code", "document"], description: "document 会使用文档交付卡样式" },
        format: { type: "string", enum: ["text", "docx", "pdf"], description: "文档格式；Word 用 docx，PDF 用 pdf" },
      summary: { type: "string", maxLength: 300, description: "文件交付卡中的简短内容摘要" },
      framework: { type: "string", maxLength: 60 },
      entry: { type: "string", maxLength: 120, description: "可选，需与 files 中的 path 对应；默认选第一个 HTML 文件或第一个文件" },
      runHint: { type: "string", maxLength: 300 },
      files: {
        type: "array", minItems: 1, maxItems: 12,
        items: {
          type: "object",
          properties: {
            path: { type: "string", maxLength: 120 },
            content: { type: "string", maxLength: 200_000 },
            contentType: { type: "string", maxLength: 120 },
          },
          required: ["path", "content"],
          additionalProperties: false,
        },
      },
    },
    required: ["files"],
    additionalProperties: false,
  },
}];

export const BLOG_GENERATION_TOOLS: AgentFunctionTool[] = [{
  name: "blog_generate",
  description: "仅在用户明确要求从主题、经历或片段新建一篇博客文章时调用。用户要求发布已有内容、当前文章或上传的文章时，使用 create_blog_draft；不要把已有文章改写成新文章。普通写文章、润色、总结和文件生成直接在对话或对应文件工具中完成。默认 mode=auto：普通主题使用 quick；只有用户明确要求深入、完整、带资料/引用，或内容涉及最新事实、法律、医疗、金融等需要核验时才用 full。",
  parameters: {
    type: "object",
    properties: {
      input: { type: "string", maxLength: 20_000, description: "用户想写的主题、经历或项目描述的完整原文" },
      mode: { type: "string", enum: ["auto", "quick", "full"], description: "默认 auto；按用户对深度、时效性和资料核验的实际要求选择" },
    },
    required: ["input"], additionalProperties: false,
  },
}];

export const PLAN_TASKS_TOOLS: AgentFunctionTool[] = [{
  name: "plan_tasks",
  description: "为需要多个步骤的任务保存或替换完整计划。每次传入全部步骤；状态为 PENDING、IN_PROGRESS、DONE、DROPPED。最多一个步骤处于 IN_PROGRESS；DROPPED 必须附上 note。单步任务不要调用。",
  parameters: {
    type: "object",
    properties: {
      items: {
        type: "array", maxItems: 20,
        items: {
          type: "object",
          properties: {
            title: { type: "string", maxLength: 120 },
            status: { type: "string", enum: ["PENDING", "IN_PROGRESS", "DONE", "DROPPED"] },
            note: { type: "string", maxLength: 300 },
          },
          required: ["title", "status"], additionalProperties: false,
        },
      },
    },
    required: ["items"], additionalProperties: false,
  },
}];

export const IMAGE_GENERATION_TOOLS: AgentFunctionTool[] = [{
  name: "image_generate",
  description: "根据当前对话描述生成新图片，包括人物形象照、虚构角色自拍、插画、封面和示意图，保存到用户素材库。只有用户明确要求生成、画或重新制作新图时调用。‘发一张你的形象照’和讨论你的外貌后‘给我看看’是查看已保存形象照，必须使用 image_resend 的 assistant_portrait，不能自动生成。生成新图不要求用户先上传图片。不得将生成的角色形象宣称为真实拍摄的照片。",
  parameters: {
    type: "object",
    properties: {
      prompt: { type: "string", minLength: 1, maxLength: 2_000, description: "图片内容的详细描述，包含主题、风格和构图" },
      title: { type: "string", maxLength: 120, description: "素材库中显示的图片标题，可选" },
    },
    required: ["prompt"],
    additionalProperties: false,
  },
}];

export const IMAGE_RESEND_TOOLS: AgentFunctionTool[] = [{
  name: "image_resend",
  description: "仅重新发送已经存在的图片，不生成新图。重发刚才在本会话生成的图片时使用 latest_generated，由服务端读取真实素材ID，不要猜测。用户明确要看上传过的原图时使用 latest_uploaded；用户要看已有素材库图片时使用 asset_id，必须有真实已知的 assetId，不得猜测。用户要看你自己的形象照或自拍（包括讨论你的外貌后说给我看看）时使用 assistant_portrait，查找已保存的形象照片；没有照片会明确说明，绝不自动生成。",
  parameters: {
    type: "object",
    properties: {
      source: { type: "string", enum: ["latest_uploaded", "asset_id", "assistant_portrait", "latest_generated"] },
      assetId: { type: "integer", minimum: 1 },
    },
    required: ["source"], additionalProperties: false,
  },
}];

export const CONTENT_QUERY_TOOLS: AgentFunctionTool[] = [
  {
    name: "query_posts",
    description: "查询当前用户自己的博客文章，按草稿、已发布、回收站、标题关键词和平台同步状态筛选。用户询问已有文章、草稿或同步情况时使用。",
    parameters: {
      type: "object", properties: {
        status: { type: "string", enum: ["draft", "published", "trashed"] },
        search: { type: "string", maxLength: 300 },
        juejinSynced: { type: "boolean" }, csdnSynced: { type: "boolean" },
        limit: { type: "integer", minimum: 1, maximum: 50 },
        page: { type: "integer", minimum: 1 }, size: { type: "integer", minimum: 1, maximum: 50 },
      }, additionalProperties: false,
    },
  },
  {
    name: "query_plans",
    description: "查询当前用户自己的定时发文计划，可按 ACTIVE、PAUSED、RUNNING、FINISHED、FAILED、CANCELLED 筛选。",
    parameters: { type: "object", properties: { status: { type: "string", enum: ["ACTIVE", "PAUSED", "RUNNING", "FINISHED", "FAILED", "CANCELLED", "ALL"] } }, additionalProperties: false },
  },
  {
    name: "query_plan_series",
    description: "查询当前用户的系列发文草案、已启用计划和每日主题。可以按系列 ID 获取完整主题清单。",
    parameters: { type: "object", properties: {
      seriesId: { type: "integer", minimum: 1 },
      status: { type: "string", enum: ["DRAFT", "ACTIVE", "PAUSED", "CANCELLED", "FINISHED", "FAILED", "ALL"] },
    }, additionalProperties: false },
  },
  {
    name: "query_email_reminders",
    description: "查询当前用户的定时邮件提醒，可按状态和主题或收件邮箱关键词筛选。",
    parameters: { type: "object", properties: {
      status: { type: "string", enum: ["PENDING", "PAUSED", "SENDING", "SENT", "FAILED", "ALL"] },
      search: { type: "string", maxLength: 120 },
    }, additionalProperties: false },
  },
  {
    name: "query_unsynced_posts",
    description: "查询当前用户尚未成功同步到掘金或 CSDN 的文章。",
    parameters: { type: "object", properties: {
      platform: { type: "string", enum: ["juejin", "csdn"] },
      status: { type: "string", enum: ["draft", "published", "trashed"] },
      search: { type: "string", maxLength: 300 },
    }, additionalProperties: false },
  },
];

export const DELEGATE_SUBTASK_TOOLS: AgentFunctionTool[] = [{
  name: "delegate_subtask",
  description: "把需要多步只读取证的独立问题交给一个上下文隔离的子智能体，只返回它的结论。brief 必须自包含。",
  parameters: {
    type: "object",
    properties: {
      objective: { type: "string", minLength: 1, maxLength: 300 },
      brief: { type: "string", maxLength: 2000 },
      tools: { type: "array", maxItems: 8, items: { type: "string" } },
    },
    required: ["objective"],
    additionalProperties: false,
  },
}];

export const SUBTASK_MAX_STEPS = 5;
export const SUBTASK_MAX_CALLS_PER_STEP = 3;
export const SUBTASK_MAX_FINDINGS_CHARS = 4000;
export const SUBTASK_MAX_OBSERVATION_CHARS = 1200;
export const SUBTASK_READ_ONLY_TOOLS = new Set([
  "query_knowledge", "query_posts", "query_plans", "query_plan_series", "query_email_reminders",
  "query_unsynced_posts", "recall_memory", "search_web", "read_webpage",
]);

export const CONTENT_MUTATION_TOOLS: AgentFunctionTool[] = [
  {
    name: "create_blog_draft",
    description: "把用户已经提供的完整博客内容保存为当前用户的本地博客草稿。用户说发布到当前博客、保存成博客或提交博客时使用；此工具只创建草稿，不直接公开发布，也不调用外部平台。必须有真实的 title 和 content；如果只有文件名而没有文件正文，先请用户提供正文，不得生成占位文章或把错误提示保存成草稿。",
    parameters: { type: "object", properties: {
      title: { type: "string", minLength: 1, maxLength: 300 }, summary: { type: "string", maxLength: 2000 },
      content: { type: "string", minLength: 1, maxLength: 2000000 }, categoryId: { type: "string", maxLength: 200 },
      tags: { type: "array", maxItems: 8, items: { type: "string", maxLength: 100 } }, coverImage: { type: "string", maxLength: 2000 },
    }, required: ["title", "content"], additionalProperties: false },
  },
  {
    name: "create_plan_series",
    description: "创建并保存一份系列发文草案，包含每一天已确定的独立主题。默认不要传 topics，让 Content AI 为用户生成并保存完整主题清单；只有用户已提供具体逐日主题时才传 topics。创建草案不会开始发文；先把完整主题列表展示给用户检查。用户明确确认后，使用 activate_plan_series，该启用操作会请求用户审批。未指定日期时从用户时区的明天开始。",
    parameters: { type: "object", properties: {
      title: { type: "string", maxLength: 120 }, topic: { type: "string", minLength: 1, maxLength: 500 },
      days: { type: "integer", minimum: 1, maximum: 30 }, topics: { type: "array", minItems: 1, maxItems: 30, items: { type: "string", minLength: 1, maxLength: 500 } },
      startDate: { type: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$" },
      triggerTime: { type: "string", pattern: "^([01]\\d|2[0-3]):[0-5]\\d$" }, timezone: { type: "string", maxLength: 64 },
      audience: { type: "string", maxLength: 120 }, tone: { type: "string", maxLength: 60 },
      platforms: { type: "array", items: { type: "string", enum: ["local", "csdn", "juejin"] } },
      publishMode: { type: "string", enum: ["DRAFT", "REVIEW", "AUTO"] },
      aiOption: { type: "string", enum: ["DEEP", "PRACTICAL", "NEWS", "OPINION"] },
    }, required: ["topic", "days"], additionalProperties: false },
  },
  {
    name: "update_plan_series",
    description: "修改尚未启用的系列发文草案。先用 query_plan_series 查看草案和完整每日主题；启用后的系列不能通过此工具修改。",
    parameters: { type: "object", properties: {
      seriesId: { type: "integer", minimum: 1 }, title: { type: "string", maxLength: 120 },
      topic: { type: "string", maxLength: 500 }, days: { type: "integer", minimum: 1, maximum: 30 },
      topics: { type: "array", minItems: 1, maxItems: 30, items: { type: "string", minLength: 1, maxLength: 500 } },
      startDate: { type: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$" },
      triggerTime: { type: "string", pattern: "^([01]\\d|2[0-3]):[0-5]\\d$" }, timezone: { type: "string", maxLength: 64 },
      audience: { type: "string", maxLength: 120 }, tone: { type: "string", maxLength: 60 },
      platforms: { type: "array", items: { type: "string", enum: ["local", "csdn", "juejin"] } },
      publishMode: { type: "string", enum: ["DRAFT", "REVIEW", "AUTO"] },
      aiOption: { type: "string", enum: ["DEEP", "PRACTICAL", "NEWS", "OPINION"] },
    }, required: ["seriesId"], additionalProperties: false },
  },
  {
    name: "activate_plan_series",
    description: "启用一份已保存的系列发文草案，建立全部每日一次性计划。仅在用户明确确认后调用；系统会先展示并等待审批。",
    parameters: { type: "object", properties: { seriesId: { type: "integer", minimum: 1 } }, required: ["seriesId"], additionalProperties: false },
  },
  {
    name: "retry_plan_series",
    description: "重新安排该系列中失败的每日文章计划立即重试。只在用户明确要求重试时调用；可能触发本地或外部平台发布，调用前必须展示并等待用户审批。",
    parameters: { type: "object", properties: { seriesId: { type: "integer", minimum: 1 } }, required: ["seriesId"], additionalProperties: false },
  },
  {
    name: "schedule_plan",
    description: "创建定时发文计划。计划创建前必须由用户审批。用户要求每日或按时自动生成博客时使用；topic 要提炼为不超过 120 字的短标题，不要直接复制素材原文。",
    parameters: { type: "object", properties: {
      topic: { type: "string", minLength: 1, maxLength: 120 },
      topics: { type: "array", maxItems: 30, items: { type: "string", maxLength: 500 } },
      triggerTimes: { type: "array", maxItems: 5, items: { type: "string", pattern: "^([01]\\d|2[0-3]):[0-5]\\d$" } },
      triggerTime: { type: "string", pattern: "^([01]\\d|2[0-3]):[0-5]\\d$" },
      timezone: { type: "string", maxLength: 64 }, audience: { type: "string", maxLength: 120 },
      tone: { type: "string", maxLength: 60 }, syncCsdn: { type: "boolean" }, syncJuejin: { type: "boolean" },
    }, required: ["topic"], additionalProperties: false },
  },
  {
    name: "update_plan",
    description: "修改当前用户自己的定时发文计划。支持暂停、恢复、编辑；取消或删除必须先经用户审批。先用 query_plans 确认计划 ID。",
    parameters: { type: "object", properties: {
      planId: { type: "integer", minimum: 1 },
      action: { type: "string", enum: ["PAUSE", "PAUSED", "RESUME", "CANCEL", "CANCELLED", "DELETE", "EDIT"] },
      topic: { type: "string", maxLength: 120 }, topics: { type: "array", maxItems: 30, items: { type: "string", maxLength: 500 } },
      triggerTime: { type: "string", pattern: "^([01]\\d|2[0-3]):[0-5]\\d$" },
      triggerTimes: { type: "array", maxItems: 5, items: { type: "string", pattern: "^([01]\\d|2[0-3]):[0-5]\\d$" } },
      timezone: { type: "string", maxLength: 64 }, audience: { type: "string", maxLength: 120 },
      tone: { type: "string", maxLength: 60 }, syncCsdn: { type: "boolean" }, syncJuejin: { type: "boolean" },
    }, required: ["planId", "action"], additionalProperties: false },
  },
  {
    name: "send_email",
    description: "立即向邮箱发送邮件。用户要求现在或立即发邮件时使用；收件人和内容明确时调用此工具生成审批，不要只在正文中口头要求确认。主题未指定时根据正文拟定简短主题供审批。确认后按本次收件人、主题和正文执行。查询历史提醒不能代替发送，历史已发送状态不能证明本次发送。邮件服务接受不代表已送达收件箱。",
    parameters: { type: "object", properties: {
      recipientEmail: { type: "string", maxLength: 254 }, subject: { type: "string", minLength: 1, maxLength: 200 },
      message: { type: "string", minLength: 1, maxLength: 10_000 },
      contentType: { type: "string", enum: ["PLAIN", "HTML"], description: "默认 PLAIN，保留用户正文原文" },
    }, required: ["recipientEmail", "subject", "message"], additionalProperties: false },
  },
  {
    name: "email_reminder",
    description: "创建定时邮件提醒，创建前必须经用户审批。支持一次性或周期发送；用户要求在指定时间向邮箱发送邮件时使用。立即发送使用 send_email，不得查询历史记录冒充本次发送。",
    parameters: { type: "object", properties: {
      recipientEmail: { type: "string", maxLength: 254 }, subject: { type: "string", maxLength: 200 },
      message: { type: "string", minLength: 1, maxLength: 10_000 },
      frequency: { type: "string", enum: ["ONCE", "DAILY", "WEEKLY", "MONTHLY", "CUSTOM"] },
      scheduledAt: { type: "string", description: "ONCE 使用 ISO-8601 带时区时间" },
      sendTime: { type: "string", pattern: "^([01]\\d|2[0-3]):[0-5]\\d$" },
      timezone: { type: "string", maxLength: 64 }, recurrenceDay: { type: "integer", minimum: 1, maximum: 31 },
      intervalDays: { type: "integer", minimum: 1, maximum: 365 },
      contentType: { type: "string", enum: ["PLAIN", "HTML"] },
      templateId: { type: "string", enum: ["classic", "minimal", "card", "notice"] },
    }, required: ["recipientEmail", "subject", "message"], additionalProperties: false },
  },
  {
    name: "update_email_reminder",
    description: "暂停、恢复或删除当前用户自己的邮件提醒。删除不可恢复，必须先经用户审批。先用 query_email_reminders 确认任务 ID。",
    parameters: { type: "object", properties: {
      taskId: { type: "integer", minimum: 1 }, action: { type: "string", enum: ["PAUSE", "PAUSED", "RESUME", "DELETE"] },
    }, required: ["taskId", "action"], additionalProperties: false },
  },
];



