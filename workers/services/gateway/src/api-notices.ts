/** Additional public business notices shared by the API language boundary. */
export const businessNotices: Record<string, string> = Object.fromEntries(`
定位授权结果格式不正确|Invalid location authorization result format
天气查询服务尚未配置|The weather query service is not configured
天气查询参数格式不正确|Invalid weather query parameters
天气查询时区无效|Invalid weather query time zone
请提供城市名称或完整经纬度，不要混用|Provide either a city name or complete coordinates without mixing them
当前会话已变化，无法创建定位授权请求|The conversation has changed. Unable to create a location authorization request
天气数据服务暂时无法连接，请稍后重试|Unable to connect to the weather data service. Please try again later
天气数据服务暂时不可用|The weather data service is temporarily unavailable
天气数据服务返回内容过大|The weather data service response exceeds the size limit
天气数据服务返回了无效数据|The weather data service returned invalid data
请提供一个城市名称|Please provide a city name
地点查询没有返回有效坐标|The location query returned no valid coordinates
天气查询坐标无效|Invalid weather query coordinates
天气数据暂缺|Weather data is currently unavailable
该日期暂时没有可用的天气预报|No weather forecast is currently available for this date
天气服务标识尚未配置，请设置可联系的产品网址或邮箱|The weather service identity is not configured. Set a contactable product URL or email address
天气地点服务尚未初始化|The weather location service has not been initialized
天气地点服务当前请求较多，请稍后重试|The weather location service is busy. Please try again later
天气服务地址配置无效|Invalid weather service URL configuration
文章生成模式无效|Invalid article generation mode
博客模型响应超时，请稍后重试|The blog model response timed out. Please try again later
系列发文计划查询失败|Unable to load series publishing plans
系列发文计划响应无效|Invalid series publishing plan response
系列计划 ID 无效|Invalid series plan ID
计划操作参数无效|Invalid plan operation parameters
编辑计划时至少提供一个新值|Provide at least one new value when editing a plan
邮件提醒操作参数无效|Invalid email reminder operation parameters
内容服务返回了无效响应|The content service returned an invalid response
内容服务请求失败|The content service request failed
模型服务配置暂不可用|The model service configuration is temporarily unavailable
智能体事件保存失败|Unable to save the Agent event
本轮对话已结束或被替代|This conversation turn has ended or has been replaced
MCP 端点地址不是有效 URL|The MCP endpoint is not a valid URL
MCP 端点必须是无内嵌凭据的 HTTPS URL|The MCP endpoint must use HTTPS without embedded credentials
MCP 端点主机名无效或指向本地地址|The MCP endpoint hostname is invalid or points to a local address
MCP 端点不能指向非公网 IP|The MCP endpoint must not point to a private IP address
MCP 端点暂不接受 IPv6 字面地址|IPv6 literal addresses are not supported for MCP endpoints
无法验证 MCP 端点是否为公网地址|Unable to verify that the MCP endpoint is public
MCP 端点解析到非公网地址，已拒绝|The MCP endpoint resolves to a private address and was rejected
MCP 端点没有可用的公网 DNS 地址|The MCP endpoint has no usable public DNS address
MCP 服务返回了空响应|The MCP service returned an empty response
MCP 服务响应超过大小上限|The MCP response exceeds the size limit
MCP 服务响应格式不正确|Invalid MCP response format
远端 MCP 工具执行失败|The remote MCP tool failed
MCP 初始化握手失败|The MCP initialization handshake failed
MCP 响应 ID 不匹配|The MCP response ID does not match
MCP 远端返回了协议错误|The remote MCP service returned a protocol error
无法连接 MCP 服务器|Unable to connect to the MCP server
MCP 服务器重定向已拒绝|The MCP server redirect was rejected
用户身份无效|Invalid user identity
暂不支持该附件格式|This attachment format is not supported
每条消息最多支持5个附件|Each message supports up to 5 attachments
附件不存在或不属于当前用户|The attachment does not exist or does not belong to you
内容操作工具不存在|The content operation tool does not exist
此内容操作需要用户审批|This content operation requires your approval
计划请求缺少幂等标识|The plan request requires an idempotency key
邮件提醒请求缺少幂等标识|The email reminder request requires an idempotency key
内容查询工具不存在|The content query tool does not exist
内容查询服务暂不可用|The content query service is temporarily unavailable
内容操作参数包含不支持的字段|The content operation parameters contain unsupported fields
文章主题不能为空且不能超过 120 个字符|The article topic is required and must not exceed 120 characters
定时邮件提醒缺少收件邮箱、主题或正文|The scheduled email reminder requires a recipient, subject and body
系列草案需要主题和 1-30 天；开始日期应为 YYYY-MM-DD|A series draft requires a topic and 1–30 days; use YYYY-MM-DD for the start date
每日主题需要是 1-30 个非空主题|Provide 1–30 nonempty daily topics
图片生成参数无效|Invalid image generation parameters
图片生成工具缺少有效参数 prompt|The image generation tool requires a valid prompt
图片生成模型配置暂不可用|The image generation model configuration is temporarily unavailable
图片生成模型尚未配置|The image generation model is not configured
图片生成模型调用失败，请检查图片模型接口配置或供应商状态|Image generation failed. Check the model API configuration or provider status
图片已经生成，但保存到素材库失败|The image was generated but could not be saved to the media library
图片已经保存到素材库，但积分扣费记录失败|The image was saved, but its points charge could not be recorded
图片素材 ID 无效|Invalid image asset ID
找不到属于当前用户的图片素材|No image asset belonging to you was found
MCP 端点地址格式不正确|Invalid MCP endpoint URL format
MCP 端点必须是无内嵌凭据的公网 HTTPS URL|The MCP endpoint must be a public HTTPS URL without embedded credentials
MCP 端点必须使用公网 DNS 主机名|The MCP endpoint must use a public DNS hostname
请求头必须是对象|Headers must be an object
请求头最多包含 20 项|Headers must not contain more than 20 entries
请求头名称或值格式不正确|Invalid header name or value
MCP 服务器参数格式不正确|Invalid MCP server parameters
serverKey 必须为 2-20 位小写字母、数字或短横线|serverKey must contain 2–20 lowercase letters, numbers or hyphens
服务器名称不能为空且最多 120 个字符|The server name is required and must not exceed 120 characters
enabled 必须是布尔值|enabled must be a boolean
MCP 服务器参数包含未知字段|The MCP server parameters contain unknown fields
服务器标识不可修改，请新建一台服务器|The server key cannot be changed. Create a new server instead
MCP 服务器不存在|The MCP server does not exist
MCP 服务器不存在或已停用|The MCP server does not exist or is disabled
MCP 工具目录已变化，请重新发起|The MCP tool catalog has changed. Please start again
MCP 客户端功能未开启|The MCP client feature is disabled
MCP 凭据加密密钥尚未配置|The MCP credential encryption key is not configured
MCP 凭据加密密钥必须是 Base64 编码的 32 字节密钥|The MCP credential encryption key must be a Base64-encoded 32-byte key
MCP 凭据加密密钥格式不正确|Invalid MCP credential encryption key format
MCP 凭据密文格式不正确|Invalid encrypted MCP credential format
无法解密 MCP 服务器凭据|Unable to decrypt the MCP server credentials
记忆需要 memoryKey|Memory requires memoryKey
记忆需要 content|Memory requires content
长期记忆保存失败|Unable to save long-term memory
删除记忆需要 memoryKey|Deleting memory requires memoryKey
记忆操作格式不正确|Invalid memory operation format
计划步骤格式不正确|Invalid plan step format
计划最多包含 20 个步骤|A plan supports up to 20 steps
计划步骤需要有效标题、状态和备注|Plan steps require a valid title, status and notes
同一计划中只能有一个进行中的步骤|A plan can only have one step in progress
放弃的步骤必须说明原因|Provide a reason for abandoning a step
当前会话已变化，无法更新计划|The conversation has changed. The plan cannot be updated
创建知识需要有效的标题、正文和知识类型|Creating knowledge requires a valid title, content and knowledge type
知识材料 ID 格式不正确|Invalid knowledge material ID format
这条知识没有可用于出题的正文|This knowledge item has no content for generating questions
测验评分结果格式不正确|Invalid quiz grading result format
每道测验题都需要有效的题目、作答、评语和 0 到 1 之间的得分|Each quiz question requires a prompt, answer, comment and score between 0 and 1
测验总评过长|The quiz summary is too long
模型服务繁忙，请稍后重试|The model service is busy. Please try again later
模型服务凭据无效，请联系管理员|Invalid model service credentials. Please contact an administrator
模型服务暂时不可用，请稍后重试|The model service is temporarily unavailable. Please try again later
模型返回的工具参数过长|The model returned tool arguments that are too long
emit_quiz 需要非空的 questions 数组|emit_quiz requires a nonempty questions array
emit_quiz 的每道题都需要 prompt|Each emit_quiz question requires a prompt
emit_artifact 需要非空的 files 数组|emit_artifact requires a nonempty files array
emit_artifact 的每个文件都需要 path 和非空 content|Each emit_artifact file requires a path and nonempty content
当前会话正在执行，请稍后重试|This conversation is running. Please try again later
附件服务尚未配置|The attachment service is not configured
智能体模型尚未配置，请在管理后台配置 Agent 模型|The Agent model is not configured. Configure it in the admin console
智能体模型尚未配置|The Agent model is not configured
模型返回内容过长，请缩小问题范围|The model response is too long. Please narrow your question
当前一次仅支持一个已注册工具调用|Only one registered tool call is supported at a time
模型返回的工具参数格式不正确|The model returned invalid tool arguments
内容查询服务尚未配置|The content query service is not configured
内容操作服务尚未配置|The content operation service is not configured
内容操作审批服务尚未配置|The content approval service is not configured
任务计划服务尚未配置|The task planning service is not configured
图片生成服务尚未配置|The image generation service is not configured
图片素材服务尚未配置|The image asset service is not configured
当前会话没有可重新发送的用户图片|This conversation has no user image to resend
image_resend 的 source 参数无效|Invalid source parameter for image_resend
远端 MCP 审批服务尚未配置|The remote MCP approval service is not configured
远端 MCP 工具不存在或当前用户无权使用|The remote MCP tool does not exist or you do not have access
文章同步审批服务尚未配置|The article synchronization approval service is not configured
长期记忆服务尚未配置|The long-term memory service is not configured
个人知识服务尚未配置|The personal knowledge service is not configured
测验题目生成失败，请稍后重试|Unable to generate quiz questions. Please try again later
博客生成助手尚未配置|The blog generation assistant is not configured
博客生成工具缺少有效参数 input|The blog generation tool requires a valid input parameter
模型请求了未注册的工具|The model requested an unregistered tool
模型没有返回可显示的内容，请稍后重试|The model returned no displayable content. Please try again later
子任务参数格式不正确|Invalid subtask parameters
子任务目标不能为空|A subtask goal is required
个人知识查询尚未配置|Personal knowledge queries are not configured
长期记忆查询尚未配置|Long-term memory queries are not configured
内容查询尚未配置|Content queries are not configured
Skill包含未注册工具|The skill contains an unregistered tool
Skill创建失败|Unable to create the skill
Skill 标识格式不正确|Invalid skill identifier format
Skill 名称不能为空且不能超过 120 个字符|The skill name is required and must not exceed 120 characters
Skill 描述不能为空且不能超过 500 个字符|The skill description is required and must not exceed 500 characters
Skill 说明不能为空且不能超过 12000 个字符|The skill instructions are required and must not exceed 12,000 characters
当前会话已变化，无法创建工具审批|The conversation has changed. Tool approval cannot be created
远端 MCP 工具尚未配置|The remote MCP tool is not configured
远端 MCP 工具参数必须是对象|Remote MCP tool arguments must be an object
远端 MCP 工具参数过长|The remote MCP tool arguments are too long
内容操作参数过长|The content operation parameters are too long
审批请求不存在|The approval request does not exist
审批请求或关联的执行状态已变化|The approval request or its execution state has changed
内容操作审批参数损坏|The content approval parameters are corrupted
远端 MCP 审批参数损坏|The remote MCP approval parameters are corrupted
文章同步参数格式不正确|Invalid article synchronization parameters
文章同步仅支持有效文章 ID 和 CSDN/掘金平台|Article synchronization requires a valid article ID and CSDN or Juejin
请求 JSON 无效|Invalid request JSON
预览服务配置无效|Invalid preview service configuration
预览页面地址配置无效|Invalid preview page URL configuration
请求内容无效|Invalid request content
HTML 代码不能为空|HTML code is required
HTML 代码超过大小限制|The HTML code exceeds the size limit
预览暂不可用，请稍后重试|The preview is temporarily unavailable. Please try again later
预览会话 ID 无效|Invalid preview session ID
预览已过期或不存在|The preview has expired or does not exist
预览内容校验失败|Preview content verification failed
文章请求载荷过大|The article request body is too large
请求体不能为空|A request body is required
文章请求格式不正确|Invalid article request format
站外同步请求载荷过大|The external synchronization request body is too large
站外同步服务尚未配置内网凭证|Internal credentials for external synchronization are not configured
站外同步服务配置无效|Invalid external synchronization service configuration
站外同步服务必须使用 HTTPS|The external synchronization service must use HTTPS
站外同步服务暂时无法连接|The external synchronization service is temporarily unreachable
站外同步服务返回了无效结果|The external synchronization service returned an invalid result
博客模型阶段结果不是有效 JSON|The blog model stage result is not valid JSON
博客模型阶段结果格式无效|Invalid blog model stage result format
模型未返回可用的文章标题或正文|The model returned no usable article title or content
请输入主题或日记|Please enter a topic or journal entry
请求幂等标识不能超过 64 个字符|The request idempotency key must not exceed 64 characters
博客生成助手任务不存在|The blog generation task does not exist
当前文章尚未准备好，不能修改|The article is not ready for revision
当前任务状态不能启动|The task cannot be started in its current state
任务状态已变化，请重试|The task state has changed. Please try again
博客生成助手执行权已失效|The blog generation execution lease has expired
文章正文超过 D1 内嵌上限，OSS 存储尚未配置|The article exceeds the D1 inline limit and OSS storage is not configured
文章尚未生成完成|The article has not finished generating
博客正文 OSS 存储尚未配置|OSS storage for blog content is not configured
文章标题格式不正确|Invalid article title format
文章摘要格式不正确|Invalid article summary format
文章分类格式不正确|Invalid article category format
文章分类不存在|The article category does not exist
封面图片地址格式不正确|Invalid cover image URL format
文章标签格式不正确|Invalid article tag format
文章正文格式不正确|Invalid article content format
文章正文不能超过 2 MiB|Article content must not exceed 2 MiB
至少提供一个需要更新的字段|Provide at least one field to update
状态不能为空|A status is required
不支持的文章状态|Unsupported article status
仅回收站中的文章可以彻底删除|Only articles in the recycle bin can be permanently deleted
无权操作该文章|You do not have permission to modify this article
状态参数必须是 0、1 或 -1|The status must be 0, 1 or -1
用户 ID 无效|Invalid user ID
媒体 URL 无效|Invalid media URL
仅接受 HTTPS 图片素材|Image assets must use HTTPS URLs
图片内容不能为空|Image content is required
图片大小不能超过 20MB|The image must not exceed 20 MB
仅支持有效的 PNG、JPEG、WebP 或 GIF 图片|Only valid PNG, JPEG, WebP or GIF images are supported
生成元数据过长|The generation metadata is too long
生成元数据格式无效|Invalid generation metadata format
定时发文计划不存在|The scheduled publishing plan does not exist
计划正在执行，请稍后重试|The plan is running. Please try again later
一次性计划不支持手动触发|One-time plans cannot be triggered manually
博客模型没有生成可发布的标题和正文|The blog model did not generate a publishable title and content
计划引用的知识材料读取服务尚未配置|The service for reading plan knowledge materials is not configured
计划引用的知识材料不存在或无法读取|The plan's knowledge material does not exist or cannot be read
系列草案请求标识无效|Invalid series draft request ID
只有草案可以修改|Only drafts can be edited
草案已变化，请刷新后重试|The draft has changed. Please refresh and try again
系列草案已启用或已变化|The series draft is already enabled or has changed
系列文章数量超过当前计划额度，请减少篇数或清理已有计划|The series exceeds your plan quota. Reduce its size or remove existing plans
系列草案已被其他请求启用|The series draft was enabled by another request
系列操作无效|Invalid series operation
当前系列没有可重试的失败计划|This series has no failed plans to retry
当前系列状态无法执行该操作|This operation is unavailable in the current series state
系列计划不存在|The series plan does not exist
每日主题需要是 1-30 个完整主题，且数量与计划天数一致|Provide 1–30 complete daily topics, matching the number of plan days
每日主题不能重复|Daily topics must be unique
系列天数需在 1-30 之间|The series must span 1–30 days
时区无效|Invalid time zone
开始日期格式应为 YYYY-MM-DD|The start date must use YYYY-MM-DD
发布时间格式应为 HH:mm|The publishing time must use HH:mm
不支持的 AI 写作方向|Unsupported AI writing direction
发布模式无效|Invalid publishing mode
发布平台选择无效|Invalid publishing platform selection
选择的平台尚未开放发布|Publishing is not available for the selected platform
每天最多设置5个发送时间，格式应为 HH:mm|Set up to 5 daily delivery times using HH:mm
计划日期格式应为 YYYY-MM-DD|The plan date must use YYYY-MM-DD
计划日期无效|Invalid plan date
一次性计划必须设置执行日期|A one-time plan requires an execution date
一次性计划的执行时间必须晚于当前时间|A one-time plan must be scheduled in the future
每周计划必须设置执行日期|A weekly plan requires execution days
无法计算计划的下次执行时间|Unable to calculate the next execution time
请输入文章主题|Please enter an article topic
计划开关值无效|Invalid plan enabled value
计划类型只支持 DAILY / WEEKLY / ONCE|The plan type must be DAILY, WEEKLY or ONCE
一次性和每周计划必须设置执行日期|One-time and weekly plans require execution dates
不支持的 AI 规划方向|Unsupported AI planning direction
幂等请求标识格式不正确|Invalid idempotency request key format
知识材料 ID 无效|Invalid knowledge material ID
博客选题服务尚未配置|The blog topic service is not configured
主题数量需在 1-30 之间|The number of topics must be between 1 and 30
博客选题服务暂不可用|The blog topic service is temporarily unavailable
计划天数需在 1-30 之间|The plan must span 1–30 days
触发时间格式应为 HH:mm|The trigger time must use HH:mm
每日主题必须完整且不重复，数量需与计划天数一致|Daily topics must be complete, unique and match the number of plan days
定时发文计划数量已达到上限，请先暂停或删除部分计划|The publishing plan limit has been reached. Pause or delete some plans first
过去24小时创建的定时发文计划已达到上限|The publishing plan creation limit for the past 24 hours has been reached
该幂等请求标识已被删除计划使用，请生成新的标识|This idempotency key was used by a deleted plan. Generate a new key
计划执行中、已取消、已完结或已失败，暂不能修改|Running, cancelled, completed or failed plans cannot be edited
计划状态已变化，请刷新后重试|The plan state has changed. Please refresh and try again
计划操作无效|Invalid plan operation
计划操作只支持 PAUSED / RESUME / CANCELLED|The plan operation must be PAUSED, RESUME or CANCELLED
当前状态无法执行该操作|This operation is unavailable in the current state
计划正在执行，暂不能删除|The plan is running and cannot be deleted yet
一次性计划只能预览一个执行时间|Only one execution time can be previewed for a one-time plan
计划执行记录不存在|The plan execution record does not exist
知识材料验证服务尚未配置|The knowledge material validation service is not configured
知识材料不存在或无权访问|The knowledge material does not exist or you do not have access
知识材料验证服务暂不可用|The knowledge material validation service is temporarily unavailable
幂等键格式不正确|Invalid idempotency key format
该幂等键已用于其他文章内容|This idempotency key was used for different article content
任务发送时间无效|Invalid task delivery time
邮件提醒格式不正确|Invalid email reminder format
收件邮箱格式不正确|Invalid recipient email address
邮件主题格式不正确|Invalid email subject format
邮件内容格式不正确|Invalid email body format
发送频率只支持 ONCE / DAILY / WEEKLY / MONTHLY / CUSTOM|The delivery frequency must be ONCE, DAILY, WEEKLY, MONTHLY or CUSTOM
发送时间格式应为 HH:mm|The delivery time must use HH:mm
请选择每周几发送（1-7，周一到周日）|Select a weekday from 1 (Monday) to 7 (Sunday)
请选择每月几号发送（1-31）|Select a day of the month from 1 to 31
自定义间隔天数需在 1-365 之间|The custom interval must be between 1 and 365 days
请选择有效的发送时间|Please select a valid delivery time
该幂等请求标识已被删除任务使用，请生成新的标识|This idempotency key was used by a deleted task. Generate a new key
定时邮件任务已达到创建上限|The scheduled email task creation limit has been reached
不支持的任务状态筛选|Unsupported task status filter
请选择任务状态|Please select a task status
任务状态只支持 PENDING 或 PAUSED|The task status must be PENDING or PAUSED
当前状态无法暂停|The task cannot be paused in its current state
当前状态无法恢复|The task cannot be resumed in its current state
计划发送时间已过，请删除后重新创建任务|The delivery time has passed. Delete and recreate the task
无法恢复该周期任务|Unable to resume this recurring task
任务状态已变化，请刷新后重试|The task state has changed. Please refresh and try again
邮件正在发送，暂时不能删除|The email is being sent and cannot be deleted yet
定时邮件任务不存在|The scheduled email task does not exist
已达到 CSDN 每日同步上限|The daily CSDN synchronization limit has been reached
CSDN 同步过于频繁，请稍后再试|CSDN synchronization is too frequent. Please try again later
站外发布正在处理中|External publishing is in progress
文章正文不可用|The article content is unavailable
站外发布记录已被其他请求接管|Another request has taken over this external publishing record
文章正文超过 Worker 传输限制|The article content exceeds the Worker transfer limit
博客选题请求参数无效|Invalid blog topic request parameters
测验服务暂不可用|The quiz service is temporarily unavailable
测验会话 ID 格式不正确|Invalid quiz session ID format
测验会话不存在|The quiz session does not exist
测验会话不属于当前对话，请重新开始测验|The quiz session belongs to another conversation. Please start a new quiz
测验会话已过期，请重新开始测验|The quiz session has expired. Please start a new quiz
测验正在评分，请稍后重试|The quiz is being graded. Please try again later
必须逐题给出题目、用户作答和得分，没有证据的分数不予记录|Provide a prompt, user answer and score for each question; unsupported scores cannot be recorded
每道题都要写清题目|Provide a clear prompt for every question
每道题都要给出得分 credit（0 到 1），不能留空|Every question requires a credit score between 0 and 1
知识不存在|The knowledge item does not exist
博客模型选题结果格式无效|Invalid blog model topic result format
博客模型请求参数无效|Invalid blog model request parameters
博客模型研究阶段参数无效|Invalid blog model research stage parameters
背诵音转文功能尚未启用|Recitation transcription is disabled
背诵材料不存在|The recitation material does not exist
录音请求标识不能为空|A recording request ID is required
录音请求标识不能超过64个字符|The recording request ID must not exceed 64 characters
无法创建背诵任务|Unable to create the recitation task
录音上传失败，请稍后重试|Unable to upload the recording. Please try again later
录音请求标识已用于另一条知识|The recording request ID was used for another knowledge item
仅支持 mp3、aac、wav、m4a、ogg、opus 或 amr 录音|Recordings must use mp3, aac, wav, m4a, ogg, opus or amr
背诵原文必须包含中文、字母或数字|The recitation text must contain Chinese characters, letters or numbers
材料标题格式不正确|Invalid material title format
材料标题不能超过100个字符|The material title must not exceed 100 characters
背诵原文格式不正确|Invalid recitation text format
背诵原文不能为空|Recitation text is required
背诵原文不能超过10000个字符|Recitation text must not exceed 10,000 characters
背诵音转文处理器尚未配置|The recitation transcription processor is not configured
录音任务不存在|The recording task does not exist
不支持的知识类型|Unsupported knowledge type
不支持的测试方式|Unsupported test mode
微信发货通知格式无效|Invalid WeChat delivery notification format
微信虚拟支付尚未在管理台启用|WeChat virtual payment is not enabled in the admin console
订阅商品不存在或未启用|The subscription product does not exist or is disabled
微信支付身份服务暂不可用|The WeChat payment identity service is temporarily unavailable
创建虚拟支付订单失败|Unable to create the virtual payment order
支付订单不存在|The payment order does not exist
微信虚拟支付尚未配置|WeChat virtual payment is not configured
微信虚拟支付查单暂不可用|WeChat virtual payment order queries are temporarily unavailable
微信订单实付金额与本地订单不一致|The paid WeChat amount does not match the local order
微信支付订单信息不完整|The WeChat payment order information is incomplete
微信发货通知字段无效|Invalid WeChat delivery notification fields
微信发货通知与本地订单不一致|The WeChat delivery notification does not match the local order
微信支付订单号与本地订单不一致|The WeChat payment order number does not match the local order
订单当前状态不允许发货|Delivery is not allowed in the current order state
订阅权益发放暂不可用|Subscription entitlement delivery is temporarily unavailable
支付订单状态更新失败，请稍后重试|Unable to update the payment order state. Please try again later
Content 发布服务尚未配置|The content publishing service is not configured
Content 发布服务地址无效|Invalid content publishing service URL
Content 发布服务必须使用 HTTPS|The content publishing service must use HTTPS
站外同步结果暂不可确认|The external synchronization result cannot be confirmed yet
Content 发布服务鉴权失败，请检查服务凭证配置|Content publishing service authentication failed. Check the service credentials
Content 发布服务响应无效|Invalid content publishing service response
Content 发布服务返回了无效结果|The content publishing service returned an invalid result
CSDN 接口响应过大|The CSDN API response is too large
CSDN 接口响应格式无效|Invalid CSDN API response format
CSDN 授权已失效|CSDN authorization has expired
CSDN 接口签名尚未配置|CSDN API signing is not configured
CSDN 接口暂不可用|The CSDN API is temporarily unavailable
CSDN 账号信息格式无效|Invalid CSDN account information format
CSDN 发布凭证尚未配置|CSDN publishing credentials are not configured
文章图片数量超过限制|The article contains too many images
文章格式无法转换|The article format cannot be converted
CSDN 发布响应缺少文章信息|The CSDN publishing response has no article information
CSDN 图片上传返回了无效地址|The CSDN image upload returned an invalid URL
CSDN 请求签名失败|CSDN request signing failed
掘金目录返回了无效 ID|The Juejin category returned an invalid ID
掘金目录返回了无效名称|The Juejin category returned an invalid name
掘金接口响应过大|The Juejin API response is too large
掘金接口响应格式无效|Invalid Juejin API response format
掘金授权已失效|Juejin authorization has expired
掘金账号接口暂不可用|The Juejin account API is temporarily unavailable
掘金账号信息格式无效|Invalid Juejin account information format
掘金分类标签格式无效|Invalid Juejin category tag format
掘金标签格式无效|Invalid Juejin tag format
掘金草稿创建响应无效|Invalid Juejin draft creation response
掘金发布响应无效|Invalid Juejin publishing response
掘金目录响应格式无效|Invalid Juejin category response format
掘金接口地址无效|Invalid Juejin API URL
平台扫码授权尚未配置浏览器服务|The browser service for platform QR authorization is not configured
浏览器授权服务返回了无效会话|The browser authorization service returned an invalid session
浏览器授权服务返回了无效 Cookie 状态|The browser authorization service returned an invalid cookie state
平台扫码授权服务暂时不可用|The platform QR authorization service is temporarily unavailable
浏览器授权服务返回了无效响应|The browser authorization service returned an invalid response
浏览器授权操作失败|The browser authorization operation failed
平台授权会话参数无效|Invalid platform authorization session parameters
平台浏览器服务地址配置无效|Invalid platform browser service URL configuration
浏览器授权响应超过允许大小|The browser authorization response exceeds the size limit
CSDN 授权已失效，请重新授权|CSDN authorization has expired. Please authorize again
CSDN 文章载荷非法|Invalid CSDN article body
掘金目录游标未推进|The Juejin category cursor did not advance
掘金目录返回空数据|The Juejin category returned empty data
掘金草稿创建失败|Unable to create the Juejin draft
掘金文章发布失败|Unable to publish the Juejin article
无法取得平台授权会话|Unable to obtain the platform authorization session
平台授权会话状态异常|Unexpected platform authorization session state
平台授权会话已取消|The platform authorization session was cancelled
微信公众号授权已失效，请重新绑定|WeChat Official Account authorization has expired. Please link it again
暂未读取到微信公众号账号资料，请重新绑定|WeChat Official Account details could not be read. Please link it again
平台授权已失效，请重新授权|Platform authorization has expired. Please authorize again
平台凭证无法读取，请重新授权|The platform credentials cannot be read. Please authorize again
授权已更改，请重新验证账号|Authorization has changed. Please verify the account again
微信公众号账号资料格式不正确|Invalid WeChat Official Account profile format
微信公众号授权已更改，请重新读取账号资料|WeChat Official Account authorization has changed. Please reload its profile
平台授权会话无法读取|The platform authorization session cannot be read
平台凭证格式不正确|Invalid platform credential format
平台凭证信息不完整|The platform credential information is incomplete
未检测到该平台的登录会话|No sign-in session was found for this platform
平台凭证加密密钥尚未配置|The platform credential encryption key is not configured
平台凭证加密密钥配置无效|Invalid platform credential encryption key configuration
授权任务已过期，请重新发起|The authorization task has expired. Please start again
授权任务不存在|The authorization task does not exist
授权任务已处理或已取消|The authorization task has been processed or cancelled
授权任务参数无效|Invalid authorization task parameters
文件不能为空|A file is required
文件大小超过限制（最大 50MB）|The file exceeds the 50 MB size limit
文件上传失败，请稍后重试|Unable to upload the file. Please try again later
批量上传文件数量无效|Invalid batch upload file count
批量上传总大小超过 50MB|The total batch upload size exceeds 50 MB
导出类型不正确|Invalid export type
`.trim().split("\n").map(line => {
  const separator = line.indexOf("|");
  return [line.slice(0, separator), line.slice(separator + 1)];
}));
