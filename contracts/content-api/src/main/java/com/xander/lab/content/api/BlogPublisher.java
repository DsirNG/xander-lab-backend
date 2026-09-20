package com.xander.lab.content.api;

/**
 * 发布端口（SPI）：把一个外部平台的发布动作抽象成一次调用。
 *
 * <p>这个接口<b>必须住在中立模块</b>，不能住在 {@code publishing.domain}。原因是它的实现
 * 分散在两个未来的部署单元里：{@code LocalBlogPublisher} 在内容域
 * （{@code dinqor-content-automation}），而 {@code CsdnPublisher} / {@code JuejinPublisher}
 * 在 integration 域（{@code dinqor-platform-integration}）。拆服务之后，integration 就会变成
 * "在实现一个内容域拥有的接口" —— 依赖方向反了。</p>
 *
 * <p>调用方是 {@code publishing.application.PublicationService}（内容域），它把 Spring 注入的
 * {@code List<BlogPublisher>} 按 {@link #platform()} 收成 Map 再按平台名取用。也就是说本接口是
 * <b>宿主定义、插件实现</b>的经典 SPI 形态。</p>
 *
 * <p><b>失败语义</b>：{@link PublishResult} 没有错误字段，所以失败只能靠抛异常表达。这是既有行为，
 * 不是新规定：{@code PublicationService.publish} 捕获 {@code RuntimeException}，把发布记录置为
 * {@code FAILED} / {@code PUBLISH_FAILED} 后重新抛出。实现必须遵守 —— 返回一个"看起来成功"的
 * 空结果会让调用方写进一条 SUCCEEDED 记录，而外部平台其实什么都没收到。</p>
 *
 * <p><b>⚠️ 未决问题（拆服务之前必须决定）。</b>
 * 现在是单体，Spring 能把两个域的实现 bean 一起注入进内容域的 Service；拆成两个进程之后这不可能成立。
 * 两个方向：① 发布适配器跟着端口走、并入内容域；② 发布改成对外的 HTTP / 事件调用。无论选哪个都
 * 不改变本接口的形状，所以先把接口抽出来是安全的。</p>
 */
public interface BlogPublisher {

    /** 平台标识，如 {@code local} / {@code csdn} / {@code juejin}；调用方用它做路由键。 */
    String platform();

    /** 执行一次发布；失败抛异常，不要返回"成功的空结果"。 */
    PublishResult publish(PublishCommand command);
}
