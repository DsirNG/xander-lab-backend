package com.xander.lab.recitation.spi;

/**
 * 跨域检索/统计背诵材料的筛选条件。
 *
 * <p><b>为什么只有四个分量</b>。提供方那个 {@code RecitationService.MaterialQuery} 有五个分量，
 * 第五个是 {@code ArchiveFilter}（在用的 / 已归档的 / 都看）。跨域消费方**从不使用**它：
 * 两处调用用的都是四参数构造器（该构造器把归档维度默认成 {@code ACTIVE}）与 {@code all()} 工厂。
 * 所以本类型只带四个分量，由提供方换算成四参数构造器 —— 落到的 SQL 谓词逐字相同
 * （{@code ACTIVE} ⇒ {@code archivedAt IS NULL}）。把归档维度带过界等于把一个消费方
 * 根本不参与的策略搬到端口上，正是端口宽度规则要求留在原地的部分。</p>
 *
 * <p><b>为什么不直接搬 {@code MaterialQuery}</b>。搬它就等于把 {@code ArchiveFilter}
 * 一起搬进契约，而那是提供方自己的词表；而且提供方还需要保留带归档维度的版本给自己的
 * 控制器（回收视图要用），所以只能是一份收窄的副本。见
 * {@code docs/migration/adr-contract-boundaries.md} §2.6。</p>
 *
 * <p><b>为什么叫 Filter 而不是 Query</b>。提供方的 {@code MaterialQuery} 是
 * {@code RecitationService} 的嵌套类型，实现类里两个类型会同时在作用域内。同名不同包会让人
 * 分不清 {@code searchMaterials} 的两个重载谁收谁。{@code MaterialFilter} 同时更贴切：
 * 提供方自己的 Javadoc 就把它称作"筛选条件"。</p>
 *
 * @param search       标题或正文的模糊匹配词；空表示不过滤
 * @param masteryLevel 掌握等级精确匹配；空表示不过滤。**大小写不敏感**——提供方会
 *                     {@code trim().toUpperCase(Locale.ROOT)} 后再比，所以传 {@code "mastered"}
 *                     与 {@code "MASTERED"} 等价
 * @param dueOnly      只要已到复习时间的条目
 * @param limit        明细条数上限；**只对 {@link RecitationMaterialPort#searchMaterials} 有意义**，
 *                     统计不看它。提供方会把它夹到 {@code [1, 自己的分页上限]} 之间
 */
public record MaterialFilter(
        String search,
        String masteryLevel,
        boolean dueOnly,
        int limit) {

    /**
     * 不带任何过滤的查询，用于"一共多少条"这类全库统计。
     *
     * <p>{@code limit} 故意给 {@link Integer#MAX_VALUE} 而不是硬编码提供方的分页上限：
     * 提供方会把上限夹到自己的页大小，所以两种写法在检索与统计上**结果相同**，
     * 但这样契约里不出现提供方的策略常量——页大小是提供方可以改的东西，
     * 一旦它从 100 改成 50，硬编码 100 的写法就会与提供方自己的 {@code all()} 悄悄分家。
     * 统计路径本来就不看 {@code limit}，连这一点差异都不存在。</p>
     */
    public static MaterialFilter all() {
        return new MaterialFilter(null, null, false, Integer.MAX_VALUE);
    }
}
