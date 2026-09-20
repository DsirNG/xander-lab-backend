package com.xander.lab.integration.api;

/**
 * 对象存储端口：上传与删除。
 *
 * <p><b>为什么要有这个接口。</b>{@code OssService} 原本被四个域以**具体类**注入
 * （agent / knowledgebase / publishing / recitation，分属四个部署单元）。
 * 拆分之后，那等于四个服务依赖另一个服务的实现类 —— 依赖方向是反的。
 * 把端口搬到这里之后，调用方只依赖一个接口：将来它由 HTTP 客户端实现（真正的服务间调用）
 * 还是由同进程的 bean 实现，都只是装配问题，调用方代码不用改。</p>
 *
 * <p><b>为什么参数是 {@code byte[]} 而不是 Spring 的 {@code MultipartFile}。</b>
 * 契约被每个服务依赖，包括 不能沾 spring-webmvc 的 WebFlux Gateway；
 * 把 {@code MultipartFile} 放进来会让 spring-web 出现在所有服务（含 Gateway）的
 * classpath 上，而且它本来就不是服务间调用的形状 —— 跨进程时没有 MultipartFile 可传。
 * 调用方手上是 {@code MultipartFile} 的，在调用点取字节转换。</p>
 *
 * <p><b>实现约定（属于契约的一部分）：</b></p>
 * <ul>
 *   <li>{@link #upload} 失败时**抛异常**，不返回"失败的结果对象"——
 *       {@link UploadResponse} 里没有表达失败的字段，所以"没抛"就等于"成功了"。
 *       调用方不要靠检查返回值的某个字段来判断成败。</li>
 *   <li>{@link #deleteByUrlQuietly} **不抛异常**，失败只返回 {@code false}
 *       并记日志：删不掉的对象交给存储侧生命周期规则回收，不能阻断调用方的业务删除。</li>
 *   <li>返回的 {@link UploadResponse#getUrl()} 是**外部可访问地址**，
 *       而 {@link UploadResponse#getStoredName()} 才是存储键名；两者用途不同。</li>
 * </ul>
 */
public interface OssPort {

    /**
     * 上传一段内容到对象存储。
     *
     * @param content      文件内容
     * @param originalName 原始文件名（用来推导扩展名）
     * @param contentType  MIME 类型
     * @param pathPrefix   路径前缀，例如 {@code "photos/blog/"}
     * @return 上传结果
     */
    UploadResponse upload(byte[] content, String originalName, String contentType, String pathPrefix);

    /**
     * 按访问地址尽力删除一个对象，失败只记日志。
     *
     * <p>只删地址前缀等于本存储域名的对象：地址是从库里读出来的，不校验前缀就直接
     * 截路径的话，一条被改写过的记录就能让我们去删别处的 key。</p>
     *
     * @return 是否确实完成了删除
     */
    boolean deleteByUrlQuietly(String url);
}
