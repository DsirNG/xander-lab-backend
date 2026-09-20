package com.xander.lab.blogagent.spi;

/**
 * 一次图片生成的完整结果：图片本体 + 描述它"是怎么被生成的"那几项。
 *
 * <h2>为什么这几个分量必须一起过界</h2>
 *
 * <p>消费方（{@code ImageGenerationTool}）拿到图之后要做三件事：把字节存进素材库、
 * 按用量扣积分、把 {@code model} / {@code size} 写进素材的 {@code meta}。
 * 前两件的身份（{@code userId}）由别的端口承担（{@code MediaAssetPort} /
 * {@code BillingPort} 都显式收 {@code userId}），第三件要的元数据只有提供方知道。</p>
 *
 * <p>如果端口只返回字节，消费方就得自己去读 {@code blog.agent} 那组
 * {@code @ConfigurationProperties} —— 那是把<b>提供方的配置</b>当成了<b>跨域契约</b>：
 * 配置项可以随便改名、可以缺省、可以整个不存在（网关路径下就是这样），
 * 而契约不能。所以让提供方适配器把"这次生成实际用了什么"填进结果里。</p>
 *
 * <h2>⚠️ {@code model} 是实际端点模型，不是配置里的首选模型</h2>
 *
 * <p>提供方的 {@code generate} 会在候选端点之间<b>兜底重试</b>，所以真正产出这张图的
 * 可能是第二个端点。填 {@code model} 的必须是实际那一个 —— 由提供方适配器从
 * {@code GeneratedImage.model()} 取，而不是从 {@code properties.getImageModel()} 取。
 * 两者只在"没发生兜底"且"走环境变量路径"时才相等；网关路径下
 * {@code properties.getImageModel()} 可能是空的。</p>
 *
 * <h2>⚠️ {@code size} 是请求尺寸，不是实测尺寸</h2>
 *
 * <p>提供方向模型请求的尺寸（如 {@code 1536x1024}）。提供方不解析返回图片的真实像素，
 * 所以这里记的是"请求了什么"，不是"得到了什么"。想要实测尺寸得解图片头，
 * 那是另一件事。</p>
 *
 * <h2>⚠️ 分量顺序：前五个与提供方的 {@code GeneratedImage} 逐位对齐，{@code size} 追加在末尾</h2>
 *
 * <p>{@code (bytes, contentType, extension, usage, model)} 与
 * {@code BlogAgentImageClient.GeneratedImage} 的顺序逐位相同，所以提供方适配器写的是一行
 * 位置映射；{@code size} 是契约侧新增的，<b>追加在末尾而不是插在 {@code model} 旁边</b> ——
 * 插在中间会让"逐位对齐"这件事失效，而失效了不会有编译错误。</p>
 *
 * <p><b>⚠️ 位置 2、3 是两个相邻的 {@code String}，位置 5、6 也是。</b>重排不会有任何编译错误，
 * 只会把 {@code contentType} 当成 {@code extension} 存下去、或者把尺寸当成模型名写进 meta。
 * 由 {@code RecordShapeTest} 用<b>互不相同</b>的字面量钉住每个位置。</p>
 *
 * <h2>为什么 {@code bytes} 是 {@code byte[]} 而不是别的</h2>
 *
 * <p>提供方本来就是 {@code byte[]}（{@code MediaAssetPort.recordGenerated} 也收
 * {@code byte[]}，先例已存在）。跨进程传 {@code byte[]} 是唯一需要留意的成本：
 * 跨进程之后它要走网络，体积上限由提供方守（10MB），消费方不需要知道这个数字。</p>
 *
 * @param bytes       图片字节，非空
 * @param contentType MIME 类型，如 {@code image/png}
 * @param extension   文件扩展名，如 {@code png}
 * @param usage       这次调用的 token 用量；网关没返回时是 {@link BlogAgentImageUsage#NONE}
 * @param model       实际产出这张图的端点模型；提供方拿不到时为 {@code null}
 * @param size        向模型请求的尺寸，如 {@code 1536x1024}
 */
public record BlogAgentImage(byte[] bytes, String contentType, String extension,
                             BlogAgentImageUsage usage, String model, String size) {
}
