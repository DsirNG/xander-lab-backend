package com.xander.lab.recitation.spi;

/**
 * 跨域创建一条背诵材料的入参。
 *
 * <p><b>为什么不是直接收 {@code recitation.api.CreateRecitationMaterialRequest}</b>。
 * 那个 record 带 {@code @NotBlank} / {@code @Size}，属于 HTTP 边界的东西：它们生效是因为
 * {@code RecitationController} 在参数上标了 {@code @Valid}。契约模块不许带注解
 * （契约必须与框架无关，{@code CONTRACT_ALLOWED_IMPORT_PREFIXES} 只有 {@code java.}），
 * 而且这条缝上的调用方**本来就没有那层校验**——工具从 JSON 取值、自己手工校验后才调进来。
 * 所以本类型是同一个四字段、去掉注解的一份；域侧那个 request 类型原样保留，
 * 继续做 HTTP 调用方的校验入口。</p>
 *
 * <h2>分量顺序是位置契约</h2>
 * <p>顺序与 {@code CreateRecitationMaterialRequest} 逐字相同，四个都是 {@code String}，
 * 编译器不会替你守顺序（写反不报错，只会静默存错字段）。同样由
 * {@code RecordShapeTest}（钉名字与顺序）和 {@code RecitationMaterialPortMappingTest}
 * （用互不相同的字面量钉那一行换算）两道防线守着。</p>
 *
 * <h2>"没传"与"传了空"</h2>
 * <p>{@code testMode} 为 {@code null} 是有语义的：表示"不给考法，由提供方按
 * {@code knowledgeType} 自动匹配"（{@code KnowledgeTestMode.fromNullable}）。
 * 换算层**不许**给它兜底成某个默认字符串——兜底会让"自动匹配"退化成"固定一种考法"，
 * 而且不报错。{@code CreateKnowledgeTool} 传的就是 {@code null}。</p>
 *
 * @param title         标题，必填
 * @param content       背诵原文，必填
 * @param knowledgeType 知识类型（RECITATION / CONCEPT / MATH）；无法判断时调用方给 CONCEPT
 * @param testMode      测试方式；{@code null} 表示按知识类型自动匹配，不是"空字符串"
 */
public record MaterialDraft(
        String title,
        String content,
        String knowledgeType,
        String testMode) {
}
