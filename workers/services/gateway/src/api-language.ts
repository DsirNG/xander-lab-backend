import { businessNotices } from "./api-notices.js";

type ApiLanguage = "zh-CN" | "en";

/** Public API notices only; business data and generated content are never translated here. */
const notices: Record<string, string> = {
  "success": "操作成功",
  "Route not found": "接口不存在",
  "Not Found": "资源不存在",
  "Origin is not allowed": "不允许此来源访问",
  "CORS method is not allowed": "不允许此跨域请求方法",
  "Service authentication required": "服务身份验证失败",
  "Invalid request body": "请求内容格式不正确",
  "Invalid language preference": "语言设置无效",
  "Invalid article ID": "文章 ID 无效",
  "Invalid reminder ID": "提醒 ID 无效",
  "Invalid user ID": "用户 ID 无效",
  "Invalid limit": "数量限制无效",
  "Invalid page or size": "分页参数无效",
  "Invalid page, size, or status": "分页或状态参数无效",
  "Only POST is allowed": "只支持 POST 请求",
  "requestId is required": "请求标识不能为空",
  "Login credential is invalid or already used": "登录凭证无效或已使用",
  "QR code is expired or already confirmed": "二维码已过期或已确认",
  "QR ticket is invalid": "二维码凭证无效",
  "Content service is temporarily unavailable": "内容服务暂不可用",
  "Article content exceeds the Worker delivery limit": "文章内容超过投递大小限制",
  "OSS configuration is incomplete": "OSS 配置不完整",
  "OSS read check failed": "OSS 读取检查失败",
  "OSS region is not configured": "OSS 区域尚未配置",
  "OSS upload failed": "OSS 上传失败",
  "Unable to reach OSS": "无法连接 OSS",
  "This diagnostic route does not accept a request body": "此诊断接口不接受请求内容",
  "Set confirm=true to move this post to the recycle bin.": "请确认后将文章移入回收站",
};

// Exact notices retain actionable validation details without interpreting user content.
const chineseNotices: Record<string, string> = {
  "未登录或登录已过期": "You are signed out or your session has expired",
  "无权限访问": "You do not have permission to access this resource",
  "无权访问": "You do not have permission to access this resource",
  "请求过于频繁，请稍后再试": "Too many requests. Please try again later",
  "操作过于频繁，请稍后再试": "Too many operations. Please try again later",
  "刷新请求过于频繁，请稍后再试": "Too many session refresh requests. Please try again later",
  "积分不足，请先获取积分": "Insufficient points. Please add points first",
  "验证码错误或已过期": "The verification code is incorrect or has expired",
  "验证码发送失败，请稍后重试": "Unable to send the verification code. Please try again later",
  "用户名或密码错误": "Incorrect username or password",
  "用户名或密码错误；旧账号请先重置密码": "Incorrect username or password. Please reset the password for a legacy account",
  "当前密码不正确；旧账号请使用邮箱重置": "Incorrect current password. Please reset a legacy account password by email",
  "账号已被禁用，请联系管理员": "This account is disabled. Please contact an administrator",
  "账号已停用": "This account is disabled",
  "账号暂时无法创建": "Unable to create the account right now",
  "账号绑定失败，请重试": "Unable to link the account. Please try again",
  "登录类型不正确": "Invalid sign-in type",
  "账号不能为空或过长": "The account must not be empty or too long",
  "邮箱格式不正确": "Invalid email address",
  "邮箱或验证码格式不正确": "Invalid email address or verification code",
  "该邮箱已注册": "This email address is already registered",
  "该邮箱尚未绑定账户": "No account is linked to this email address",
  "该用户名已被使用": "This username is already taken",
  "用户名不能为空或超过 50 个字符": "The username is required and must not exceed 50 characters",
  "用户名长度需在 3-30 个字符之间": "The username must contain 3–30 characters",
  "用户名只能包含字母、数字及 _@.+-": "The username may only contain letters, numbers and _@.+-",
  "密码长度为 6-50 个字符": "The password must contain 6–50 characters",
  "新密码长度需在 6-72 个字符之间": "The new password must contain 6–72 characters",
  "密码格式不正确": "Invalid password format",
  "昵称不能为空": "A display name is required",
  "昵称长度不能超过 30 个字符": "The display name must not exceed 30 characters",
  "不能修改自己的账号状态": "You cannot change your own account status",
  "不能封禁管理员账号": "Administrator accounts cannot be disabled",
  "状态只能是 0（封禁）或 1（解封）": "The status must be 0 (disabled) or 1 (enabled)",
  "当前账号不是可绑定邮箱的微信账号": "This WeChat account cannot be linked to an email address",
  "当前账号已绑定邮箱": "This account is already linked to an email address",
  "当前账号未绑定微信小程序身份": "This account is not linked to a WeChat Mini Program identity",
  "该微信账号和邮箱已分别绑定不同账户": "This WeChat account and email address are linked to different accounts",
  "该微信账号已有独立账户，请先完成账户数据迁移后再绑定": "This WeChat identity already has an account. Migrate its account data before linking",
  "该微信账号已绑定其他邮箱": "This WeChat account is linked to another email address",
  "该邮箱已绑定其他微信账号": "This email address is linked to another WeChat account",
  "绑定凭证或验证码格式不正确": "Invalid linking credential or verification code",
  "绑定凭证无效或已过期，请重新登录": "The linking credential is invalid or expired. Please sign in again",
  "微信登录凭证不能为空": "A WeChat sign-in credential is required",
  "微信登录凭证无效": "Invalid WeChat sign-in credential",
  "微信登录加密凭据不可用": "The encrypted WeChat sign-in credentials are unavailable",
  "审批请求参数不正确": "Invalid approval parameters",
  "微信登录未配置，请联系管理员": "WeChat sign-in is not configured. Please contact an administrator",
  "微信支付身份校验失败，请重新登录后再试": "WeChat payment identity verification failed. Please sign in again",
  "微信二维码服务暂不可用，请稍后重试": "WeChat QR codes are temporarily unavailable. Please try again later",
  "微信登录服务暂不可用，请稍后重试": "WeChat sign-in is temporarily unavailable. Please try again later",
  "微信加密密钥未配置，无法保存凭据": "The WeChat encryption key is not configured. Credentials cannot be saved",
  "微信登录加密凭据不可用，请检查 WECHAT_CREDENTIAL_ENCRYPTION_KEY": "The encrypted WeChat sign-in credentials are unavailable. Check WECHAT_CREDENTIAL_ENCRYPTION_KEY",
  "refreshToken 不能为空或过长": "The refresh token is required and must not be too long",
  "API 基础地址必须以 http(s):// 开头": "The API base URL must start with http:// or https://",
  "API 基础地址过长": "The API base URL is too long",
  "API 密钥不能为空": "An API key is required",
  "API 密钥过长": "The API key is too long",
  "主模型与兜底模型不能相同（同供应商+同模型时兜底无意义，请配置其他供应商或模型）": "The primary and fallback models must use different providers or models",
  "功能配置未启用时请清空模型槽位，或先启用该配置": "Clear model slots for a disabled feature, or enable the feature first",
  "该供应商仍被功能模型配置引用，请先移除相关配置再删除": "Remove this provider from feature model configurations before deleting it",
  "模型供应商管理尚未启用，请配置 ADMIN_MODEL_GATEWAY_ENABLED=true 并应用数据库迁移": "Model provider management is disabled. Set ADMIN_MODEL_GATEWAY_ENABLED=true and apply the database migrations",
  "供应商名称不能为空且不超过 100 个字符": "The provider name is required and must not exceed 100 characters",
  "供应商名称已存在": "This provider name already exists",
  "模型名称不能为空或过长": "The model name is required and must not be too long",
  "模型名不能超过 100 个字符": "The model name must not exceed 100 characters",
  "模型供应商加密密钥未正确配置": "The model provider encryption key is not configured correctly",
  "图片模型未返回 token 用量，无法按 token 计费": "The image model did not return token usage, so token billing is unavailable",
  "图片生成功能不支持 RESPONSES 接口风格": "Image generation does not support the RESPONSES API style",
  "文本功能不支持图片专用接口风格": "Text features do not support image-only API styles",
  "DASHSCOPE_ASR 仅用于背诵音转文功能": "DASHSCOPE_ASR is only supported for recitation transcription",
  "背诵音转文功能只支持 DASHSCOPE_ASR 接口风格": "Recitation transcription only supports the DASHSCOPE_ASR API style",
  "单个附件不能超过20MB": "Each attachment must not exceed 20 MB",
  "消息内容不能超过 4000 个字符": "The message must not exceed 4,000 characters",
  "消息内容和附件不能同时为空": "Provide message text or an attachment",
  "分享链接无效或已失效": "The share link is invalid or has expired",
  "修改要求不能为空": "Revision instructions are required",
  "附件不能为空": "An attachment is required",
  "上传文件不能为空": "An upload file is required",
  "不支持的文件类型": "Unsupported file type",
  "不支持的文章操作": "Unsupported article operation",
  "图片内容为空或超过 10MB": "The image is empty or exceeds 10 MB",
  "图片内容为空或超过 20MB": "The image is empty or exceeds 20 MB",
  "图片大小不能超过 10MB": "The image must not exceed 10 MB",
  "请选择有效的图片文件": "Please select a valid image file",
  "备注不能超过 500 个字符": "Notes must not exceed 500 characters",
  "头像地址必须以 http(s):// 开头": "The avatar URL must start with http:// or https://",
  "头像地址过长": "The avatar URL is too long",
  "一次最多提交 50 个标签": "You can submit up to 50 tags at a time",
  "tagIds 数量过多": "Too many tag IDs",
  "文件名不能为空": "A file name is required",
  "文件名最长 255 个字符": "The file name must not exceed 255 characters",
  "文件大小必须大于 0": "The file size must be greater than zero",
  "文件超过大小上限 50MB": "The file exceeds the 50 MB limit",
  "文件夹名不能为空": "A folder name is required",
  "文件夹名不能包含斜杠": "The folder name must not contain slashes",
  "文件夹名最长 120 个字符": "The folder name must not exceed 120 characters",
  "同级目录下已有同名文件夹": "A folder with this name already exists here",
  "不能把文件夹移动到它自己的子目录下": "A folder cannot be moved into one of its own subfolders",
  "不能把文件夹移动到自己下面": "A folder cannot be moved into itself",
  "根目录不能直接上传文件，请先选择一个文件夹": "Select a folder before uploading files",
  "根目录不能直接放文件，请先选择一个文件夹": "Select a folder before adding files",
  "文件校验值应为 64 位 SHA-256 十六进制串": "The file checksum must be a 64-character SHA-256 hexadecimal string",
  "非法的文件路径": "Invalid file path",
  "无法安全定位该文件的 OSS 对象": "The file's OSS object cannot be located safely",
  "无法安全定位该文件的正文对象": "The file's content object cannot be located safely",
  "该文件的正文对象不存在": "The file's content object does not exist",
  "该文件的正文对象尚不可用": "The file's content object is not available yet",
  "该文件已有上传会话，请稍后重试": "This file already has an upload session. Please try again later",
  "上传会话已完成，但文件记录暂不可用": "The upload is complete, but the file record is temporarily unavailable",
  "上传会话已结束，不能继续上传": "This upload session has ended",
  "上传会话已过期，请重新初始化": "This upload session has expired. Please start a new upload",
  "上传会话状态已变化，请重试": "The upload session has changed. Please try again",
  "上传正在合并中，暂不能放弃": "The upload is being merged and cannot be cancelled yet",
  "上传正在合并中，请稍后重试": "The upload is being merged. Please try again later",
  "该会话已完成上传，无法放弃": "This upload is complete and cannot be cancelled",
  "OSS 对象大小与文件声明不一致，会话已作废，请重新上传": "The OSS object size does not match the declared file size. Start a new upload",
  "分片状态与文件声明不一致，请重新上传": "The chunk state does not match the declared file. Please upload it again",
  "分片内容不能为空": "Chunk content is required",
  "分片内容过大": "The chunk content is too large",
  "标签名不能为空": "A tag name is required",
  "标签名最长 60 个字符": "The tag name must not exceed 60 characters",
  "标签说明最长 200 个字符": "The tag description must not exceed 200 characters",
  "标签已存在": "This tag already exists",
  "授权任务令牌无效或已过期": "The authorization task token is invalid or has expired",
  "录音文件不能为空": "An audio recording is required",
  "录音文件或请求标识不能为空": "An audio recording and request ID are required",
  "录音文件过大": "The audio recording is too large",
  "背诵服务拒绝了该操作": "The recitation service rejected this operation",
  "分页参数无效": "Invalid pagination parameters",
  "分页参数超出范围": "Pagination parameters are out of range",
  "请求内容过大": "The request content is too large",
  "上传请求超过大小限制": "The upload request exceeds the size limit",
  "请求方法不支持": "Unsupported request method",
  "业务类型不能为空": "A business type is required",
  "协议类型不能为空": "A protocol type is required",
  "色值格式应为 #RRGGBB": "The color must use the #RRGGBB format",
  "PLUS、PRO、ULTRA 商品配置必须完整": "Product configurations for PLUS, PRO and ULTRA must be complete",
  "PLUS、PRO、ULTRA 商品配置格式不正确": "Invalid product configurations for PLUS, PRO or ULTRA",
  "递归删除包含 OSS 文件的批量清理流程尚未迁移": "Recursive deletion of folders containing OSS files is not available yet",
  "当前 Worker runtime 不支持 WebSocket": "This Worker runtime does not support WebSocket",
};

// Repeated validation/service notices use a bounded subject dictionary, never arbitrary text substitution.
const subjects: Record<string, string> = {
  "身份服务": "Identity service", "身份服务限流": "Identity rate limiting",
  "请求限流服务": "Rate limiting service", "邮件服务": "Email service",
  "个人博客统计服务": "Blog statistics service", "微信加密密钥": "WeChat encryption key",
  "微信小程序凭据": "WeChat Mini Program credentials", "微信登录服务配置": "WeChat sign-in configuration",
  "微信登录加密凭据": "Encrypted WeChat sign-in credentials", "微信凭据": "WeChat credentials",
  "虚拟支付配置": "Virtual payment configuration", "供应商": "Provider", "功能模型配置": "Feature model configuration",
  "博客模型服务": "Blog model service", "博客生成助手服务": "Blog generation assistant",
  "媒体服务": "Media service", "定时发文计划服务": "Scheduled publishing service",
  "定时邮件服务": "Scheduled email service", "平台授权服务": "Platform authorization service",
  "掘金发布服务": "Juejin publishing service", "掘金目录同步": "Juejin category synchronization",
  "CSDN 发布服务": "CSDN publishing service", "支付服务": "Payment service", "文件服务": "File service",
  "文章保存": "Article saving", "文章服务": "Article service", "文章管理": "Article management",
  "智能体服务": "Agent service", "模型定价": "Model pricing", "知识库文件记录": "Knowledge file record",
  "知识库": "Knowledge base", "站外同步": "External synchronization", "系列计划服务": "Series plan service",
  "背诵任务处理": "Recitation processing", "背诵服务": "Recitation service", "计划执行器": "Plan executor",
  "通知保存": "Notification saving", "通知广播": "Notification broadcasting", "通知广播服务": "Notification broadcast service",
  "通知服务": "Notification service", "邮件投递": "Email delivery", "阿里云 OSS": "Alibaba Cloud OSS",
  "附件存储": "Attachment storage", "预览服务": "Preview service", "录音上传": "Audio upload", "附件上传": "Attachment upload",
  "Agent 任务 ID": "Agent task ID", "Skill ID": "Skill ID", "Skill 工具列表": "Skill tool list", "Skill": "Skill",
  "AppID": "AppID", "AppKey": "AppKey", "AppSecret": "AppSecret", "OfferID": "OfferID",
  "archived 参数": "archived parameter", "chapterId 参数": "chapterId parameter", "dueOnly 参数": "dueOnly parameter",
  "limit 参数": "limit parameter", "page 参数": "page parameter", "priority": "Priority", "recursive 参数": "recursive parameter",
  "size 参数": "size parameter", "tagIds 参数": "tagIds parameter", "上传会话": "Upload session", "上传请求": "Upload request",
  "会话 ID": "Conversation ID", "会话": "Conversation", "会话分页参数": "Conversation pagination parameters",
  "会话或审批 ID": "Conversation or approval ID", "会话订阅参数": "Conversation subscription parameters",
  "供应商 ID": "Provider ID", "内容类型": "Content type", "凭证属主参数": "Credential owner parameter",
  "分片内容": "Chunk content", "分片大小": "Chunk size", "分片序号": "Chunk index",
  "博客模型请求载荷": "Blog model request body", "博客生成助手任务 ID": "Blog generation task ID",
  "博客生成助手请求参数": "Blog generation request parameters", "博客生成助手请求": "Blog generation request",
  "商品ID或微信登录凭证": "Product ID or WeChat sign-in credential", "图片上传信息": "Image upload information",
  "图片": "Image", "图片名称": "Image name", "图片标题": "Image title", "图片生成信息": "Image generation information",
  "图片生成元数据": "Image generation metadata", "头像地址": "Avatar URL", "媒体 ID": "Media ID", "媒体信息": "Media information",
  "审批请求参数": "Approval parameters", "平台账号名称": "Platform account name", "平台账号资料": "Platform account profile",
  "录音任务 ID": "Recording task ID", "录音请求": "Recording request", "微信支付身份请求": "WeChat payment identity request",
  "执行记录 ID": "Execution ID", "批量上传请求": "Batch upload request", "掘金分类参数": "Juejin category parameters",
  "掘金标签参数": "Juejin tag parameters", "文件": "File", "文件夹": "Folder", "文章 ID": "Article ID", "文章": "Article",
  "文章内容": "Article content", "文章属主参数": "Article owner parameter", "文章更新参数": "Article update parameters",
  "文章编号": "Article ID", "材料 ID": "Material ID", "材料": "Material", "标签": "Tag", "标签列表": "Tag list",
  "标签名": "Tag name", "标签说明": "Tag description", "模型名称": "Model name", "模型定价参数": "Model pricing parameters",
  "注册赠送参数": "Registration reward parameters", "测验请求": "Quiz request", "父文件夹": "Parent folder",
  "用户 ID": "User ID", "用户": "User", "用户名": "Username", "站外同步文章载荷": "External article body",
  "站外同步请求参数": "External synchronization parameters", "站外同步请求": "External synchronization request",
  "章节": "Chapter", "系列 ID": "Series ID", "系列计划请求内容": "Series plan request body", "系列计划请求": "Series plan request",
  "背诵材料 ID": "Recitation material ID", "计划 ID": "Plan ID", "计划请求载荷": "Plan request body", "计费参数": "Billing parameters",
  "订阅权益请求": "Subscription entitlement request", "请求内容": "Request body", "账号合并参数": "Account merge parameters",
  "通知 ID": "Notification ID", "通知": "Notification", "通知游标": "Notification cursor", "通知请求": "Notification request",
  "附件信息": "Attachment information", "附件请求": "Attachment request", "预览次数": "Preview count",
};

const suffixes: Array<[string, (subject: string) => string]> = [
  ["格式不正确", s => `Invalid ${s.toLowerCase()} format`],
  ["格式无效", s => `Invalid ${s.toLowerCase()} format`],
  ["无效", s => `Invalid ${s.toLowerCase()}`],
  ["不存在或已归档", s => `${s} does not exist or has been archived`],
  ["不存在", s => `${s} does not exist`],
  ["尚未配置", s => `${s} is not configured`],
  ["未配置", s => `${s} is not configured`],
  ["暂时不可用", s => `${s} is temporarily unavailable`],
  ["暂不可用", s => `${s} is temporarily unavailable`],
  ["响应无效", s => `${s} returned an invalid response`],
  ["保存失败", s => `Unable to save ${s.toLowerCase()}`],
  ["暂时失败", s => `${s} failed temporarily. Please try again later`],
  ["失败", s => `${s} failed`],
  ["不能为空", s => `${s} is required`],
  ["过长", s => `${s} is too long`],
  ["过大", s => `${s} is too large`],
  ["非法", s => `Invalid ${s.toLowerCase()}`],
];

export function englishNotice(message: string): string | undefined {
  if (Object.hasOwn(businessNotices, message)) return businessNotices[message];
  if (Object.hasOwn(chineseNotices, message)) return chineseNotices[message];
  if (Object.hasOwn(notices, message)) return message === "success" ? "Success" : message;
  for (const [suffix, render] of suffixes) {
    if (!message.endsWith(suffix)) continue;
    const name = message.slice(0, -suffix.length).trim();
    const subject = Object.hasOwn(subjects, name) ? subjects[name] : undefined;
    if (subject) return render(subject);
  }
  if (message === "enabled 必须为布尔值") return "enabled must be a boolean";
  return undefined;
}

/** Selects supported languages by quality weight; missing/unsupported preferences default to Chinese. */
export function apiLanguage(header: string | null): ApiLanguage {
  const candidates = (header || "").split(",").map((part, index) => {
    const [tag, ...parameters] = part.trim().toLowerCase().split(";");
    const qualityParameter = parameters.find(value => value.trim().startsWith("q="));
    const quality = qualityParameter ? Number(qualityParameter.trim().slice(2)) : 1;
    return { tag, quality, index };
  }).filter(item => Number.isFinite(item.quality) && item.quality > 0 && item.quality <= 1)
    .sort((left, right) => right.quality - left.quality || left.index - right.index);
  for (const { tag } of candidates) {
    if (/^en(?:-[a-z0-9]+)*$/.test(tag)) return "en";
    if (/^zh(?:-[a-z0-9]+)*$/.test(tag)) return "zh-CN";
  }
  return "zh-CN";
}

function fallbackNotice(status: number, language: ApiLanguage): string {
  const pairs: Record<number, [string, string]> = {
    400: ["请求参数不正确，请检查后重试", "Invalid request. Please check your input"],
    401: ["未登录或登录已过期", "You are signed out or your session has expired"],
    403: ["无权限执行此操作", "You do not have permission to perform this action"],
    404: ["请求的资源不存在", "The requested resource does not exist"],
    409: ["资源状态已变化，请刷新后重试", "The resource has changed. Please refresh and try again"],
    413: ["请求内容过大", "The request content is too large"],
    429: ["请求过于频繁，请稍后再试", "Too many requests. Please try again later"],
  };
  const pair = pairs[status] || (status >= 500
    ? ["服务暂不可用，请稍后重试", "The service is temporarily unavailable. Please try again later"]
    : status < 400 ? ["操作成功", "Success"] : ["请求失败，请稍后重试", "The request failed. Please try again later"]);
  return pair[language === "en" ? 1 : 0];
}

/** Localizes only public JSON envelopes, leaving streams, files, WebSocket upgrades and data untouched. */
export async function localizeApiResponse(response: Response, request: Request): Promise<Response> {
  if (!new URL(request.url).pathname.startsWith("/api/") || response.status === 101
    || !response.headers.get("content-type")?.toLowerCase().includes("application/json")
    || response.headers.has("content-disposition")) return response;
  let body: unknown;
  try { body = await response.clone().json(); } catch { return response; }
  if (!body || typeof body !== "object" || Array.isArray(body)) return response;
  const envelope = body as Record<string, unknown>;
  if (typeof envelope.code !== "number" || typeof envelope.message !== "string"
    || !Object.hasOwn(envelope, "data")) return response;
  const language = apiLanguage(request.headers.get("accept-language"));
  const message = envelope.message;
  const status = response.status < 400 && ![0, 200].includes(envelope.code) ? 400 : response.status;
  // Existing Chinese notices retain their meaning; untranslated/third-party notices use a safe local fallback.
  envelope.message = language === "en"
    ? englishNotice(message) || fallbackNotice(status, language)
    : Object.hasOwn(notices, message) ? notices[message]
      : /[\u3400-\u9fff]/.test(message) ? message : fallbackNotice(status, language);
  const headers = new Headers(response.headers);
  headers.set("content-language", language);
  const vary = headers.get("vary");
  if (!vary?.split(",").some(value => value.trim().toLowerCase() === "accept-language")) {
    headers.set("vary", vary ? `${vary}, Accept-Language` : "Accept-Language");
  }
  // The body changed, so upstream representation validators and lengths no longer apply.
  for (const header of ["content-length", "content-encoding", "etag", "content-md5", "digest"]) headers.delete(header);
  return new Response(JSON.stringify(envelope), { status: response.status, statusText: response.statusText, headers });
}
