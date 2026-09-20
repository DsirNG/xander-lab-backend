package com.xander.lab.content.api;

import java.util.List;

/**
 * 分页的文章列表。
 *
 * <h2>为什么不用 {@code com.xander.lab.common.PageData}</h2>
 * <p>两个原因，任一都足以否决：</p>
 * <ol>
 *   <li><b>契约不能依赖它。</b>{@code PageData} 住在 {@code libs/xander-mybatis-support}，
 *       那个模块为它声明了 MyBatis-Plus（构造器直接收 {@code IPage}）。契约只允许 JDK，
 *       门禁 {@code contract-dependency-boundary} 会拦。</li>
 *   <li><b>它自己也跨不了进程。</b>{@code PageData} 的存在理由是“简化 MyBatis-Plus 的
 *       {@code IPage}，只保留前端需要的字段”——那是<b>前端</b>分页返回体，
 *       与服务间分页是两件事。教训就是别让一个类型同时服务两个受众。</li>
 * </ol>
 *
 * <p>本记录逐个字段镜像 {@code PageData} 的序列化表面（{@code records} / {@code total} /
 * {@code current} / {@code pages} / {@code size} / {@code hasMore}），理由与
 * {@link ContentPost} 完全一样：换类型后线上 JSON 不变，这次抽取才可证明是纯重构。
 * 字段集是否与 {@code PageData} 一致由 {@code ContentPostShapeTest} 钉住。</p>
 *
 * <p><b>⚠️ 同样刻意不做规范化</b>（{@code records} 为 {@code null} 时保持 {@code null}）：
 * 镜像的判据是序列化逐字节不变，而 {@code "records":null} 与 {@code "records":[]} 是
 * 两种不同的输入。详见 {@link ContentPost} 的同名小节。</p>
 *
 * <p><b>⚠️ 分量顺序是位置契约。</b>{@code total} / {@code current} / {@code pages} /
 * {@code size} 是四个相邻 {@code long}，重排不会有任何编译错误，只会让“总页数”
 * 显示成“每页大小”。顺序由 {@code ContentPostPageTest} 反射钉住。</p>
 *
 * @param records 当前页的数据
 * @param total   总记录数
 * @param current 当前页码，从 1 开始
 * @param pages   总页数
 * @param size    每页大小
 * @param hasMore 是否还有下一页（{@code current < pages}）
 */
public record ContentPostPage(
        List<ContentPost> records,
        long total,
        long current,
        long pages,
        long size,
        boolean hasMore) {
}
