package com.xander.lab.content.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 一篇文章的服务间读模型。
 *
 * <h2>为什么是 {@code publishing.api.BlogPostVO} 的镜像，而不是收窄</h2>
 * <p>本记录逐个字段镜像 {@code BlogPostVO} 的<b>可序列化</b>表面：同名、同类型、同顺序，
 * 只少了 {@code tagsRaw}。这样做的目的是让调用方从 {@code BlogPostVO} 换到本类型时
 * 线上 JSON 不变 —— 换类型这件事因此可以被 diff 证明是安全的。</p>
 *
 * <p>“收窄到前端真正读的字段”<b>也能做</b>，但那会改前端拿到的响应形状，
 * 应当单独一次做，不能夹在别的工作里顺手做掉。
 * 先例是 {@code BlogAgentTaskVO.illustrations}：那次也选了镜像，
 * 理由与这里一样。</p>
 *
 * <h2>为什么没有 {@code tagsRaw}</h2>
 * <p>{@code BlogPostVO.tagsRaw} 是 {@code BlogPostMapper} 的 {@code GROUP_CONCAT} 承接字段，
 * 靠 {@code @JsonIgnore} 挡在线上 JSON 之外。契约不能带 Jackson 注解，
 * 所以这里<b>根本没有这个字段</b> —— 这是镜像能成立的前提：漏掉一个被忽略的字段，
 * 等于把它加回线上形状。填充 {@code tags} 的责任在实现方（内容域），
 * 调用方拿到的就是最终列表。</p>
 *
 * <h2>⚠️ 刻意不做任何规范化（不要“顺手加”紧凑构造器）</h2>
 * <p>{@code PublishCommand} 有紧凑构造器把 {@code tags} 归一成空列表，本记录<b>故意没有</b>：
 * 镜像的全部价值在于“换类型后序列化逐字节不变”，而任何规范化都会改变线上形状 ——
 * {@code tags} 为 {@code null} 时线上是 {@code "tags":null}，归一成空列表就变成
 * {@code "tags":[]}，两者对前端与 MCP 客户端是不同的输入。要归一就归一在实现方的边界上，
 * 不要归一在契约的构造器里。</p>
 *
 * <p><b>⚠️ 分量顺序是位置契约，且没有编译期保护。</b>这里有四组相邻同类型字段
 * （{@code title}/{@code summary}/{@code content}/{@code category}/{@code categoryName}/
 * {@code coverImage} 是一串 {@code String}；{@code id} 与 {@code userId} 都是 {@code Long}；
 * {@code views} 与 {@code status} 都是 {@code Integer}；{@code csdnSynced} 与
 * {@code juejinSynced} 都是 {@code Boolean}）。重排不会有任何编译错误，只会静默换字段。
 * 分量名与顺序由 {@code ContentPostTest} 反射钉住；与 {@code BlogPostVO} 的字段集是否一致
 * 由 {@code ContentPostShapeTest} 钉住（那个测试必须住在应用模块，因为契约侧看不见
 * {@code BlogPostVO}）。</p>
 *
 * <h2>为什么 {@code category} 与 {@code categoryName} 都在</h2>
 * <p>前者是分类的稳定标识（如 {@code frontend}），后者是展示名（如“前端开发”）。
 * 同步到外部平台时用的是 {@code categoryName}（{@code CsdnBlogSyncService} /
 * {@code JuejinBlogSyncService} 都这么取），站点列表用的是 {@code category}。
 * 两者都在镜像范围内，删任一个都会改线上形状。</p>
 *
 * @param id             文章主键
 * @param title          文章标题
 * @param summary        文章摘要；列表投影可能为 {@code null}
 * @param content        文章正文（Markdown）；列表投影<b>不</b>返回它，只有详情投影会填充
 * @param category       分类标识，如 {@code frontend}
 * @param categoryName   分类展示名，如“前端开发”；外部平台同步用的就是它
 * @param coverImage     封面图 URL，可为 {@code null}
 * @param tags           标签名列表，序列化给前端；实现方未填充时可为 {@code null}
 * @param userId         作者用户 id
 * @param author         作者展示名，由内容域在写入时解析并落库，不由调用方提供
 * @param date           发布日期，格式 {@code yyyy-MM-dd}
 * @param readTime       预计阅读时间，如 {@code "3 min"}
 * @param tips           文章提示信息（Tips）
 * @param views          浏览次数
 * @param status         状态，取值见 {@link ContentPostStatus}
 * @param csdnSynced     是否已成功同步到 CSDN
 * @param juejinSynced   是否已成功同步到掘金
 */
public record ContentPost(
        Long id,
        String title,
        String summary,
        String content,
        String category,
        String categoryName,
        String coverImage,
        List<String> tags,
        Long userId,
        String author,
        LocalDate date,
        String readTime,
        String tips,
        Integer views,
        Integer status,
        Boolean csdnSynced,
        Boolean juejinSynced) {
}
