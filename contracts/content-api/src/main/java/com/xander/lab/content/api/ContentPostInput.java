package com.xander.lab.content.api;

import java.util.List;

/**
 * 写入一篇文章时要提交的字段。
 *
 * <p>同一个类型同时服务 {@link ContentPostPort#create} 与 {@link ContentPostPort#update}，
 * 语义差别在方法上而不在类型上：</p>
 * <ul>
 *   <li><b>create</b>：{@code title} 与 {@code content} 必填（实现方会拒绝空值），
 *       其余可空。</li>
 *   <li><b>update</b>：<b>为 {@code null} 的分量表示“不改这一项”</b>；
 *       {@code tags} 非 {@code null} 时<b>整体替换</b>该文章的全部标签
 *       （与 {@code BlogService.updateBlog} 的规则一致）。全部分量为 {@code null}
 *       时实现方抛 {@link IllegalArgumentException}。</li>
 * </ul>
 *
 * <h2>为什么抽成 DTO 而不是继续传位置参数</h2>
 * <p>{@code BlogService.updateBlog(Long, String, String, String, String, String, List)}
 * 有<b>五个相邻的 {@code String}</b>（title / summary / content / categoryId / coverImage）。
 * 把 {@code summary} 与 {@code content} 写反不会有任何编译错误 ——
 * 摘要列会拿到整篇正文，而“摘要”本身只影响列表页的一行字，很难在测试里被发现。
 * 换成具名分量之后，写错名字编译期就红。</p>
 *
 * <p><b>⚠️ 刻意不做规范化。</b>把 {@code tags} 归一成空列表会直接改掉语义：
 * {@code null} 是“不动标签”，空列表是“删光标签”。这两者必须保持可区分。
 * 同理，{@code update} 里空字符串也不是 {@code null} 的同义词 —— 前者是“改成空”，
 * 后者是“不改”。实现方按 {@code null} 判定，不要顺手用 {@code StringUtils.hasText}。</p>
 *
 * <p><b>⚠️ 分量顺序是位置契约。</b>顺序由 {@code ContentPostInputTest} 反射钉住。</p>
 *
 * @param title      标题
 * @param summary    摘要
 * @param content    正文（Markdown）
 * @param categoryId 分类标识
 * @param coverImage 封面图 URL
 * @param tags       标签名列表；{@code update} 时非 {@code null} 表示整体替换
 */
public record ContentPostInput(
        String title,
        String summary,
        String content,
        String categoryId,
        String coverImage,
        List<String> tags) {
}
