package com.xander.lab.content.api;

import java.util.List;

/**
 * 一次发布请求的载体，同时被内容域与 integration 域使用。
 *
 * <p><b>紧凑构造器里的规范化是契约的一部分，不是顺手写的便利。</b>
 * {@code tags} 为 null 时归一成空列表，非 null 时做防御性拷贝。去掉它有两个后果：
 * ① 实现方会在 {@code tags} 上遇到 NPE（平台适配器都要遍历标签）；
 * ② 调用方后续修改自己持有的 list 会<em>反过来改掉已经提交的请求</em> —— 而本记录的用途
 * 正是"记录一次已提交的发布"，它必须不可变。V9 §2.3 禁止重构静默降低既有能力，
 * 所以这条行为由 {@code PublishCommandTest} 钉住。</p>
 *
 * <p>{@code categoryId} 是外部平台的分类标识（CSDN 的分类 id），不是本地
 * {@code BlogCategory} 的主键；两者同名但不同域，拆分后不会合并。</p>
 */
public record PublishCommand(Long taskId, Long userId, String title, String summary,
                             String content, String categoryId, String coverImage, List<String> tags) {

    public PublishCommand {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
