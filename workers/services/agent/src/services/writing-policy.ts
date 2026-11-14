/** Ordinary prose stays in chat; durable blog/file work requires a user request or confirmation. */
export const WRITING_POLICY = "普通写作请求默认直接在对话中给出完整正文，并通过流式输出展示；正文末尾可按需简短提示下一步能力，例如生成文件、发布到当前博客或同步到第三方平台；提示只是引导，不代表已经执行。只有用户明确说要生成文件、发布博客、同步平台、添加配图，或明确确认紧接着的对应提示，才调用对应工具。普通写作、润色、总结和只要求 Markdown 排版都不创建博客任务、不生成文件、不配图、不发布、不同步。任何具有外部副作用的操作都必须等待用户在对话中明确表达意图。";

export function allowsBlogTask(history: { role: string; content: string }[]): boolean {
  const userIndex = history.map(item => item.role).lastIndexOf("user");
  const text = history[userIndex]?.content ?? "";
  if (/^(?:继续|执行下一步|下一步|进度|咋样了|怎么样了|continue|next step)[？?！!。\s]*$/i.test(text.trim())) {
    return allowsBlogTask(history.slice(0, userIndex));
  }
  if (/(不要|不用|不需要).{0,8}(博客|文件|插图|配图)|直接.{0,8}(内容|正文|文字)|(?:no|without) (?:blog|files?|illustrations?)/i.test(text)) return false;
  if (/(博客|blog)|(?:发布|publish).{0,12}(?:文章|博客|post)/i.test(text)) return true;
  if (!/^(好[的啊]?|可以[的啊]?|行|需要|要|开始[吧]?|就这样|yes|ok|sure|go ahead)[，,。.!！\s]*$/i.test(text.trim())) return false;
  const previous = [...history.slice(0, userIndex)].reverse().find(item => item.role === "assistant")?.content ?? "";
  return /(?:可以|需要|要不要|是否|can|would you like).{0,100}(?:博客|文件|插图|配图|blog|file|illustration)/i.test(previous.slice(-350));
}
