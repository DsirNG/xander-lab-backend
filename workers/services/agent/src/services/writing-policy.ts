/** Ordinary prose stays in chat; durable blog/file work requires a user request or confirmation. */
export const WRITING_POLICY = "写作请求默认直接在对话中给出完整正文，不要仅因为用户说写文章、生成文字、润色或总结就调用博客工具，也不要先强迫用户选择格式。完成后可简短提供可选项：整理为 Markdown 文件、做成博客、按需要添加指定张数插图；不要每次机械重复。只有用户明确要求博客任务、导出文件或配图交付，或确认了紧接着的相关提议，才调用对应工具。用户只要 Markdown 排版不等于要求导出文件。普通内容不自动配图、不自动建任务、不自动发布。";

export function allowsBlogTask(history: { role: string; content: string }[]): boolean {
  const userIndex = history.map(item => item.role).lastIndexOf("user");
  const text = history[userIndex]?.content ?? "";
  if (/(不要|不用|不需要).{0,8}(博客|文件|插图|配图)|直接.{0,8}(内容|正文|文字)|(?:no|without) (?:blog|files?|illustrations?)/i.test(text)) return false;
  if (/(博客|blog)|(?:导出|生成|做成|整理|提供|给我).{0,15}(?:文件|文档)|(?:加|配|添加).{0,8}(?:插图|配图)|(?:export|create|make).{0,20}(?:file|document)|add.{0,12}illustrations?/i.test(text)) return true;
  if (!/^(好[的啊]?|可以[的啊]?|行|需要|要|开始[吧]?|就这样|yes|ok|sure|go ahead)[，,。.!！\s]*$/i.test(text.trim())) return false;
  const previous = [...history.slice(0, userIndex)].reverse().find(item => item.role === "assistant")?.content ?? "";
  return /(?:可以|需要|要不要|是否|can|would you like).{0,100}(?:博客|文件|插图|配图|blog|file|illustration)/i.test(previous.slice(-350));
}
