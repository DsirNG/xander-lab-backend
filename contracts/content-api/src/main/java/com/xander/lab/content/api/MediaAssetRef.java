package com.xander.lab.content.api;

import java.time.LocalDateTime;

/**
 * 媒体素材的跨域引用：其他域看到的"素材库里的一条记录"。
 *
 * <p>它替代 {@code publishing.domain.BlogMediaAsset} 出现在所有跨域位置上。那个类是
 * MyBatis 实体（{@code @TableName("blog_media_asset")} / {@code @TableId}），
 * <b>根本不能进契约</b>：契约模块不许有编译期依赖，MyBatis 注解就是依赖。所以这里不是
 * "把实体换个包"，而是让实体留在 {@code publishing.domain}，由
 * {@code BlogMediaService} 在边界上做一次显式映射。</p>
 *
 * <h2>⚠️ 字段集是刻意的：与实体一一镜像，不是"顺手抄一遍"</h2>
 * <p>这 13 个字段与 {@code BlogMediaAsset} 完全一致、顺序也一致，理由是<b>本提交必须是一次
 * 可验证的纯重构</b>：{@code BlogAgentTaskVO.illustrations} 是<b>前端可见</b>的 HTTP 响应
 * （{@code GET} 博客智能体任务详情），字段一变就是 V9 §11 的契约变更。镜像之后
 * 序列化结果逐字节不变，于是"重构没有改变对外行为"这件事可以被 diff 证明，而不是靠嘴说。</p>
 *
 * <p>实测前端只读了其中 3 个字段（{@code id} / {@code url} / {@code originalName}，
 * 见 {@code xander-lab-frontend} 的 {@code AgentPreviewPanel.jsx}）。所以<b>收窄是可行的</b>
 * —— {@code userId}、{@code generationMeta} 这类内部字段本来就不该出现在响应里。
 * 但收窄会改响应形状，属于 §11 流程，应当单独一次做，不要混在纯重构里
 * （混在一起就没法用 diff 证明重构本身是安全的）。</p>
 *
 * <p><b>为什么 {@code generationMeta} 是 {@code String} 而不是解析后的结构：</b>
 * 它是一个 JSON 文本列，解析逻辑（{@code AgentImageVO.from}）属于内容域，不属于契约。
 * 契约只搬运，不解释。</p>
 *
 * <p><b>契约字段改动 = 契约变更（V9 §11）。</b>尤其 {@code url} / {@code originalName} /
 * {@code storedName} 是三个相邻的 {@code String}，重排不会有任何编译错误，
 * 只会让调用方读到错的值 —— 由 {@code MediaAssetRefTest} 用互不相同的字面量钉住。</p>
 *
 * @param id             素材主键，跨域调用方靠它引用（例如 {@code remember} 工具的 mediaAssetId）
 * @param userId         归属用户；跨域调用方拿到的必然是自己传进去的那个值
 * @param url            对外可访问地址（前端直接渲染）
 * @param originalName   上传时的原始文件名
 * @param storedName     对象存储里的键名；与 {@code url} 不同，它不是公开地址
 * @param size           字节数
 * @param contentType    形如 {@code image/png}
 * @param width          像素宽；WebP 走 RIFF 兜底路径时可能为 {@code null}
 * @param height         像素高；同上
 * @param sourceType     {@code user_upload} / {@code agent_generated}
 * @param agentTaskId    智能体生成时为任务主键，用户上传时为 {@code null}
 * @param generationMeta 生成参数 JSON 原文（title / prompt / model / size），损坏时保留原文
 * @param createdAt      入库时间
 */
public record MediaAssetRef(
        Long id,
        Long userId,
        String url,
        String originalName,
        String storedName,
        Long size,
        String contentType,
        Integer width,
        Integer height,
        String sourceType,
        Long agentTaskId,
        String generationMeta,
        LocalDateTime createdAt) {
}
