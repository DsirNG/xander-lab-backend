package com.xander.lab.integration.api;

/**
 * 文件上传响应体
 * 对应前端 axios 封装中的 upload() 方法返回值
 *
 * <p>它同时是两件事的契约：</p>
 * <ul>
 *   <li><b>前端</b>的 HTTP 契约（Web / 小程序的 {@code upload()} 返回值）；</li>
 *   <li><b>服务间</b>契约 —— agent / knowledgebase / publishing / recitation 四个域
 *       都要读它的字段，而它们分属四个部署单元，所以它不能留在
 *       {@code integration.oss} 里。</li>
 * </ul>
 *
 * <p><b>为什么手写而不用 Lombok。</b>契约模块被每个服务依赖（包括不能沾 spring-webmvc 的 WebFlux Gateway），所以它的依赖清单必须保持"只有 JDK"，
 * 而 {@code contract-dependency-boundary} 门禁把这条规则做成了一条不许商量的判据。
 * 手写还带来一个真实好处：这是**上线形状**，字段名与顺序应当不需要了解 Lombok 的
 * 默认行为就能读懂。</p>
 *
 * <p>⚠️ <b>字段顺序就是构造器参数顺序，改动它等于改动契约</b>。相邻的
 * {@code url} / {@code originalName} / {@code storedName} 都是 String，重排不会产生
 * 任何编译错误，所以由 {@code UploadResponseTest} 钉住。</p>
 */
public final class UploadResponse {

    /** 文件访问 URL */
    private final String url;

    /** 原始文件名 */
    private final String originalName;

    /** 存储文件名（UUID 重命名后） */
    private final String storedName;

    /** 文件大小（字节） */
    private final long size;

    /** MIME 类型 */
    private final String contentType;

    /** 文件扩展名 */
    private final String extension;

    public UploadResponse(String url,
                          String originalName,
                          String storedName,
                          long size,
                          String contentType,
                          String extension) {
        this.url = url;
        this.originalName = originalName;
        this.storedName = storedName;
        this.size = size;
        this.contentType = contentType;
        this.extension = extension;
    }

    public String getUrl() {
        return url;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getStoredName() {
        return storedName;
    }

    public long getSize() {
        return size;
    }

    public String getContentType() {
        return contentType;
    }

    public String getExtension() {
        return extension;
    }
}
