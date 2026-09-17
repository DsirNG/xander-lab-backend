package com.xander.lab.recitation.spi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨域读取背诵材料时的只读视图。
 *
 * <p><b>收窄而非镜像</b>。{@code recitation.api.RecitationMaterialView} 原地不动，仍然由
 * {@code recitation.api.RecitationController} 原样返回给前端；本类型是**新缝上的新类型**，
 * 前端永远看不到它，所以只带跨域消费方真正读的分量。见
 * {@code docs/migration/adr-contract-boundaries.md} §2.5 与 §2.6。</p>
 *
 * <h2>九个分量是怎么推出来的</h2>
 * <p>字段集不是"看着像"挑的，是从**消费方读取点**机械反推再与视图对账得到的。
 * 三个消费方读的并集：</p>
 * <table>
 *   <caption>消费方读取点（用 {@code .name(} 的形式取出全部访问器）</caption>
 *   <tr><th>消费方</th><th>读到的分量</th></tr>
 *   <tr><td>{@code CreateKnowledgeTool}</td>
 *       <td>{@code id}、{@code title}、{@code knowledgeType}、{@code testMode}、
 *           {@code masteryScore}、{@code masteryLevel}</td></tr>
 *   <tr><td>{@code QueryKnowledgeTool}</td>
 *       <td>{@code id}、{@code title}、{@code content}、{@code knowledgeType}、{@code testMode}、
 *           {@code masteryScore}、{@code masteryLevel}、{@code reviewCount}、{@code nextReviewAt}</td></tr>
 *   <tr><td>{@code BlogPlanRunService}</td>
 *       <td>{@code title}、{@code content}</td></tr>
 *   <tr><td>{@code BlogContentPlanService}</td>
 *       <td>不读任何分量：只借这个方法确认"这条知识存在且属于当前用户"（不存在时由提供方抛 404）</td></tr>
 * </table>
 * <p>并集 = 本类型的九个分量。<b>故意不是分量的五个</b>：{@code characterCount}、
 * {@code lastReviewAt}、{@code archivedAt}、{@code createdAt}、{@code updatedAt} ——
 * 它们在跨域这一侧一个读取点都没有。其中 {@code archivedAt} 尤其值得点名：
 * 归档维度**根本不跨域**，两个消费方用的都是默认"只看在用"的口径（见
 * {@link MaterialFilter} 为什么只有四个分量）。</p>
 *
 * <h2>分量顺序是位置契约</h2>
 * <p>顺序沿用视图的字段顺序（去掉那五个之后剩下的相对次序），这样两边的对应关系一眼可见。
 * 但它是**位置契约**，编译器不会替你守：{@code id} 之后连着四个 {@code String}
 * （{@code title}/{@code content}/{@code knowledgeType}/{@code testMode}），写反了不会报错，
 * 只会让工具输出的 JSON 悄悄串位。两道防线：</p>
 * <ul>
 *   <li>本模块的 {@code RecordShapeTest} 用反射钉住**名字与顺序**；</li>
 *   <li>应用侧的 {@code RecitationMaterialPortMappingTest} 用**互不相同的字面量**
 *       钉住 {@code RecitationMaterialView -> MaterialView} 那一行换算。四个相邻的
 *       {@code String} 用一组全不相同的字面量即可（全不同 ⇒ 任何换位都至少有一处对不上）；
 *       不需要像相邻 {@code Boolean} 那样用两组互补字面量，因为 {@code Boolean} 只有两个取值。</li>
 * </ul>
 *
 * @param id            材料 id；{@code QueryKnowledgeTool} 用它拼 {@code knowledgeUrl}
 * @param title         标题；{@code BlogPlanRunService} 把它写进创作提示词
 * @param content       正文；{@code QueryKnowledgeTool} 截成摘录，{@code BlogPlanRunService} 限长 8000
 * @param knowledgeType 知识类型（RECITATION / CONCEPT / MATH）
 * @param testMode      测试方式（按类型自动选）
 * @param masteryScore  掌握分数
 * @param masteryLevel  掌握等级（NEW / FAMILIAR / RECALLING / APPLYING / MASTERED）
 * @param reviewCount   已复习次数
 * @param nextReviewAt  下次建议复习时间；为空表示还没排过复习
 */
public record MaterialView(
        Long id,
        String title,
        String content,
        String knowledgeType,
        String testMode,
        BigDecimal masteryScore,
        String masteryLevel,
        int reviewCount,
        LocalDateTime nextReviewAt) {
}
