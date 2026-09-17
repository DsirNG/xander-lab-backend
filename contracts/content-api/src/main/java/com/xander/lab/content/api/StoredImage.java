package com.xander.lab.content.api;

/**
 * 一张<b>已经写进对象存储</b>的图片，交给内容域登记进媒体库。
 *
 * <h2>为什么要有这个类型，而不是让端口收 5 个裸参数</h2>
 * <p>{@link MediaAssetPort#recordStored} 原本需要 {@code url / originalName / storedName /
 * size / contentType} 五个参数，其中 <b>三个是相邻的 {@code String}</b>。裸参数版本在调用点
 * 长得完全一样，写错顺序不会有任何编译错误 —— 只会让媒体库里存下一条
 * "原始文件名其实是存储键名"的记录，而且没人会发现。具名构造器把顺序问题变成可见问题。</p>
 *
 * <h2>⚠️ 它和 {@code integration.api.UploadResponse} 字段重合，这是刻意的</h2>
 * <p>看起来像重复定义，但不是：</p>
 * <ul>
 *   <li>{@code UploadResponse} 回答的是"把字节写进对象存储得到了什么"——是<b>集成域</b>的概念；</li>
 *   <li>{@link StoredImage} 回答的是"内容域要把什么登记进媒体库"——是<b>内容域</b>的概念。</li>
 * </ul>
 * <p>两者今天字段重合，将来不必然：对象存储换实现、媒体库多加一列，各自动各的。
 * 更重要的是<b>契约不许依赖另一个契约</b> —— {@code contract-dependency-boundary} 门禁
 * 只允许 JDK 与自身包，所以 {@code content-api} 不能 import {@code integration.api.UploadResponse}。
 * 调用方在边界上做一次字段搬运，代价是 5 行，换来的是两个契约可以独立演化。</p>
 *
 * <p><b>契约字段改动 = 契约变更（V9 §11）。</b>{@code url} / {@code originalName} /
 * {@code storedName} 三个相邻 {@code String} 重排不报错，由 {@code StoredImageTest} 钉住。</p>
 *
 * @param url          对外可访问地址
 * @param originalName 上传时的原始文件名
 * @param storedName   对象存储里的键名
 * @param size         字节数
 * @param contentType  形如 {@code image/png}
 */
public record StoredImage(
        String url,
        String originalName,
        String storedName,
        Long size,
        String contentType) {
}
