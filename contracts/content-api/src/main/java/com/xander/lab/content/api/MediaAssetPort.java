package com.xander.lab.content.api;

import java.util.List;

/**
 * 内容域对外公开的媒体库端口（V9 §11 / §18 / §91）。
 *
 * <h2>为什么这个接口必须住在中立模块</h2>
 * <p>它由 {@code publishing.application.BlogMediaService} 实现，而调用方分散在
 * <b>三个部署单元</b>：{@code agent}（{@code dinqor-agent}）、{@code blogagent}
 * （{@code dinqor-content-automation}）、{@code studio}（{@code dinqor-core-host}）。
 * 拆服务之后，让它们各自 import 内容域的具体 {@code @Service} 就是"一个服务直接调另一个服务的
 * 内部类"：类一改，三个服务一起坏，而编译期只在一个仓库里才看得见。</p>
 *
 * <h2>为什么签名里是 {@code byte[]} 而不是 {@code MultipartFile}</h2>
 * <p>{@code MultipartFile} 是 spring-web 类型。放进契约会让 spring-web 出现在<b>每个</b>依赖契约的
 * 服务（含 V9 §19 那个 WebFlux Gateway）的 classpath 上；而且<b>跨进程时根本没有
 * {@code MultipartFile} 可传</b>。所以端口只收字节 + 元信息，调用方在调用点转换。
 * 实现类保留了 {@code uploadImage(Long, MultipartFile)} 作为<b>本域内便捷重载</b>，
 * 只服务同域的 {@code BlogMediaController}。先例：{@code integration.api.OssPort}。</p>
 *
 * <h2>⚠️ {@link #recordGenerated} 为什么不收 blogagent 的生成结果类型</h2>
 * <p>实现类原本收 {@code blogagent.infrastructure.BlogAgentImageClient.GeneratedImage}，
 * 于是<b>内容域 import 了 blogagent</b>，而 blogagent 又 import 内容域的
 * {@code BlogMediaService} —— 两个域成环。把那个 record 搬进契约也不行：它的字段里有
 * {@code com.xander.lab.common.ModelUsage}，那是带 Jackson 的类型，进不了契约
 * （契约必须与序列化框架无关）。所以按 {@code OssPort} 的先例，端口只收
 * {@code byte[]} + {@code contentType}：<b>内容域从头到尾只用到这两个字段</b>，
 * 其余字段（{@code usage} / {@code model} / {@code extension}）留在生成方自己手里。
 * 环就此拆成单向。</p>
 *
 * <h2>失败语义（跨进程后仍然成立，实现必须遵守）</h2>
 * <ul>
 *   <li>{@code userId} 为 {@code null} → 抛 {@link IllegalStateException}（"未登录或登录已过期"）。
 *       六个方法都会先做这一步。</li>
 *   <li>{@link #findOwned} / {@link #findReusableGenerated} 查不到<b>返回 {@code null}，
 *       不抛异常</b>。调用方拿它做"存在性判断"，抛异常会让正常流程变成异常路径。</li>
 *   <li>{@link #uploadImage} 对不支持的格式 / 超限大小抛 {@link IllegalArgumentException}
 *       （用户输入问题）；{@link #recordGenerated} 对无法解码的图片抛
 *       {@link IllegalStateException}（服务端生成物问题，不是用户输入）。</li>
 * </ul>
 *
 * <p><b>⚠️ 端口宽度没有任何东西在守。</b>加一个 {@code default} 方法不会编译报错、不会动任何指标、
 * 门禁也不查 —— 于是"媒体库端口"会慢慢长成所有人绕过内容域边界的新入口。
 * 方法个数由 {@code MediaAssetPortTest} 用反射钉住。要加方法就显式改那个测试，
 * 让它成为一次有意识的契约扩展。</p>
 */
public interface MediaAssetPort {

    /**
     * 单张图片的字节上限（10MB）。
     *
     * <p><b>调用方必须在把上传流读成字节之前用它做前置判断。</b>端口只收 {@code byte[]}，
     * 所以"先读进内存再拒绝"会把一个本可在流上拒绝的超大请求整份缓冲下来：
     * {@code spring.servlet.multipart.max-file-size} 是 <b>100MB</b>，而这里只收 10MB ——
     * 差了 10 倍的放大。实现仍会再查一次（不能只靠调用方），但前置判断是调用方的责任。</p>
     *
     * <p>把数字放在契约里而不是让三个调用方各写一遍：上限是内容域的事实，
     * 抄成三份之后改一处就漏两处。</p>
     */
    long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /**
     * 把一张已经写进对象存储的图片登记进媒体库。
     *
     * <p>调用方是 agent 的附件上传路径：它自己通过 {@code OssPort} 把图片写进对象存储，
     * 再让内容域登记，从而拿到 {@link MediaAssetRef#id()} 供后续工具按 id 引用。</p>
     *
     * @param stored 已经存储好的图片；{@code contentType} 不是 {@code image/*} 时抛
     *               {@link IllegalArgumentException}
     */
    MediaAssetRef recordStored(Long userId, StoredImage stored);

    /**
     * 持久化一张由智能体图片生成器产出的图片。
     *
     * <p>只收字节与元信息，见类注释里"为什么不收 blogagent 的生成结果类型"。</p>
     *
     * @param taskId         关联的博客智能体任务；无任务时传 {@code null}
     * @param generationMeta 生成参数 JSON 原文，供画廊解析标题 / 提示词；可为 {@code null}
     * @param content        图片字节；无法被 {@code ImageIO} 识别时抛 {@link IllegalStateException}
     */
    MediaAssetRef recordGenerated(Long userId, Long taskId, String originalName,
                                  byte[] content, String contentType, String generationMeta);

    /**
     * 校验并存储一张用户上传的图片。
     *
     * <p>格式白名单（PNG / JPEG / WebP / GIF）与大小上限<b>在实现里</b>，不在调用方 ——
     * 否则三个调用方各写一遍，迟早漏一个。WebP 在标准 JDK 下 {@code ImageIO} 读不出来，
     * 实现会用 RIFF 签名兜底，此时 {@link MediaAssetRef#width()} 与
     * {@link MediaAssetRef#height()} 为 {@code null}。</p>
     *
     * <p><b>⚠️ 调用方在调本方法之前必须先按 {@link #MAX_IMAGE_BYTES} 拒绝超大文件</b>
     * （用 {@code MultipartFile#getSize()}，不要先 {@code getBytes()}）。理由见该常量。</p>
     */
    MediaAssetRef uploadImage(Long userId, byte[] content, String originalName, String contentType);

    /**
     * 取回一条素材，<b>且必须属于 {@code userId}</b>。
     *
     * <p>归属过滤在实现里做（SQL 层 {@code WHERE user_id = ?}），不能只靠调用方传对 userId ——
     * 这是越权读取的唯一防线。</p>
     *
     * @return 不存在、或存在但不属于该用户时为 {@code null}
     */
    MediaAssetRef findOwned(Long userId, Long assetId);

    /**
     * 取某个智能体任务下的全部素材，按主键升序。
     *
     * <p>调用方是 {@code BlogAgentTaskVO.illustrations}，直接进前端响应。</p>
     */
    List<MediaAssetRef> listByTask(Long userId, Long taskId);

    /**
     * 找出该用户最近一张插画标题匹配的生成图，用于"相关文章复用同一张图"而不重新生成。
     *
     * <p>只在 {@code source_type = agent_generated} 里找，且只在该用户自己的素材里找。
     * {@code generationMeta} 损坏或缺失时跳过该条继续扫，不抛异常 —— 一条坏数据不应该让整个
     * 复用机制失效。</p>
     *
     * @return 没有匹配时为 {@code null}
     */
    MediaAssetRef findReusableGenerated(Long userId, String title);
}
