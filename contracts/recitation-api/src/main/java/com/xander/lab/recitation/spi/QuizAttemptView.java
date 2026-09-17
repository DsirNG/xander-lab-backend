package com.xander.lab.recitation.spi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次历史测验的摘要，供跨域消费方在出题前回顾"上次问了什么、用户栽在哪一题"。
 *
 * <h2>为什么是收窄，不是镜像</h2>
 * <p>域侧 {@code recitation.api.KnowledgeQuizAttemptView} 有 9 个分量，这里只有 6 个。
 * 字段集是从**消费方读取点**反推的（唯一跨域消费方是 {@code QuizKnowledgeTool}）：</p>
 * <table>
 *   <caption>域侧 9 个分量里，跨域这一侧读了哪 6 个</caption>
 *   <tr><th>分量</th><th>跨域消费方读了吗</th></tr>
 *   <tr><td>{@code questionCount} {@code correctCount} {@code score} {@code verdict}
 *       {@code items} {@code createdAt}</td><td><b>读了</b></td></tr>
 *   <tr><td>{@code id}</td><td>没读。它是给知识页做列表主键用的</td></tr>
 *   <tr><td>{@code materialId}</td><td>没读。整份历史都属于同一个 materialId，
 *       而那个值由外层 {@link QuizSessionView#materialId()} 给</td></tr>
 *   <tr><td>{@code testMode}</td><td>没读。前端按它显示"概念题/录音"，智能体不需要</td></tr>
 * </table>
 *
 * <h2>{@code items} 为什么是文本而不是结构</h2>
 * <p>域侧那个字段的类型是 {@code Object} —— 一个**没有形状**的字段：它是
 * {@code items_json} 列经 {@code ObjectMapper.readValue(..., Object.class)} 反序列化出来的。
 * 唯一跨域消费方拿到它之后**只看结构里的东西，自己不做任何判断**，直接原样塞进工具输出
 * （{@code objectMapper.valueToTree(...)}）。也就是说消费方要的是"这段 JSON 本身"，
 * 而不是"若干道题"。</p>
 *
 * <p>契约不能出现 {@code JsonNode}（ADR-1），而这里恰好有一条现成的答案：
 * <b>JSON 以文本出入端口</b>，与 {@code agent-api} 的 {@code RemoteTool} 同一个做法。
 * 好处不只是合规：</p>
 * <ul>
 *   <li>提供方**不再解析**。它把 {@code items_json} 列的值原样交出来，
 *       于是"历史行里存的是旧形状"这件事对契约完全不可见 —— 契约不假装知道形状。</li>
 *   <li>**解析的容错归调用方**。域侧原来在反序列化失败时吞掉异常、留 {@code null}，
 *       消费方再把它变成空数组。按"端口只回答数据问题，兜底留调用方"，
 *       这个兜底本来就该在消费方那一侧。搬过去之后行为逐字不变
 *       （非法 JSON 与空列都仍然产出空数组）。</li>
 *   <li>消费方本来就持有 {@code ObjectMapper}（它是 agent 的适配器层，Jackson 归它管）。</li>
 * </ul>
 *
 * <p>⚠️ 代价：契约侧无法用类型表达"这一定是个 JSON 数组"。这条由
 * {@code QuizKnowledgeToolTest} 里"历史逐题证据必须是真的 JSON，而不是某个对象的
 * {@code toString}"那条断言守 —— 它是这个字段唯一会被写错的方式。</p>
 *
 * @param itemsJson 逐题证据的 JSON 数组原文，可能为空字符串或为 {@code null}（列里没有内容）
 */
public record QuizAttemptView(
        int questionCount,
        int correctCount,
        BigDecimal score,
        String verdict,
        String itemsJson,
        LocalDateTime createdAt) {
}
